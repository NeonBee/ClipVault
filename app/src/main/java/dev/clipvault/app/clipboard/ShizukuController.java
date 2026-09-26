package dev.clipvault.app.clipboard;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.clipvault.app.BuildConfig;

import rikka.shizuku.Shizuku;

/**
 * App-side owner of the protocol v3 clipboard UserService. Tracks {@link BridgeHealth} as
 * NO_SHIZUKU → PERMISSION_REQUIRED → BINDER_CONNECTED → API_PROBING → READY_EVENT / READY_POLL /
 * DEGRADED. State transitions run on the main thread.
 */
public final class ShizukuController implements ClipboardBridge, AutoCloseable {
    public interface StateListener {
        void onStateChanged(@NonNull BridgeHealth health);
    }

    public interface ClipboardListener {
        void onClipboardChanged(@Nullable String text);
    }

    private static final long INITIAL_RETRY_MS = 1_000L;
    private static final long MAX_RETRY_MS = 60_000L;
    /** A DEGRADED bridge is re-probed at least this often so capture resumes quickly. */
    private static final long MAX_REPROBE_MS = 15_000L;
    private static final long BIND_TIMEOUT_MS = 15_000L;
    /** Consecutive transient read failures (INVOCATION_FAILED) before the bridge is DEGRADED. */
    private static final int TRANSIENT_FAILURE_LIMIT = 3;

    private final Shizuku.UserServiceArgs serviceArgs;
    private final StateListener listener;
    private final ClipboardListener clipboardListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile IClipboardBridge bridge;
    private volatile BridgeHealth health = BridgeHealth.of(BridgeHealth.State.NO_SHIZUKU);
    private volatile boolean started;
    private boolean binding;
    private long retryDelayMs = INITIAL_RETRY_MS;
    private final Runnable reconnect = this::tryBind;
    private final Runnable reprobe = this::reprobe;
    private final Runnable bindTimeout = this::onBindTimeout;
    private volatile int transientFailures;
    /** Set when a bind attempt timed out; a late onServiceConnected for it is dropped. */
    private boolean bindAbandoned;

    private final IClipboardListener remoteListener = new IClipboardListener.Stub() {
        @Override
        public void onClipboardChanged(String text) {
            deliverClipboardChange(text);
        }
    };

    /**
     * Binder callbacks can already be in flight when {@link #close()} unregisters the listener.
     * After close (capture disabled or service destroyed) such a late clip must not be captured.
     */
    void deliverClipboardChange(@Nullable String text) {
        if (!started || clipboardListener == null) return;
        clipboardListener.onClipboardChanged(text);
    }

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::tryBind;
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> mainHandler.post(() -> {
        if (!started) return;
        bridge = null;
        binding = false;
        publish(BridgeHealth.of(BridgeHealth.State.NO_SHIZUKU));
        scheduleReconnect();
    });

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            mainHandler.removeCallbacks(bindTimeout);
            binding = false;
            if (!started) return;
            if (bindAbandoned) {
                // Arrived after the timeout already unbound and scheduled a fresh attempt.
                bindAbandoned = false;
                try {
                    Shizuku.unbindUserService(serviceArgs, connection, true);
                } catch (RuntimeException ignored) {
                    // Shizuku may already be stopped.
                }
                return;
            }
            IClipboardBridge candidate = IClipboardBridge.Stub.asInterface(binder);
            try {
                if (candidate == null) throw new RemoteException("Null bridge");
                if (candidate.protocolVersion() != ClipboardBridgeProtocol.VERSION) {
                    bridge = null;
                    publish(BridgeHealth.degraded(ClipboardBridgeProtocol.PROTOCOL_MISMATCH));
                    scheduleReconnect();
                    return;
                }
                bridge = candidate;
                publish(BridgeHealth.of(BridgeHealth.State.BINDER_CONNECTED));
                probe(candidate);
            } catch (RemoteException error) {
                bridge = null;
                publish(BridgeHealth.of(BridgeHealth.State.NO_SHIZUKU));
                scheduleReconnect();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mainHandler.removeCallbacks(bindTimeout);
            bridge = null;
            binding = false;
            if (!started) return;
            publish(BridgeHealth.of(BridgeHealth.State.NO_SHIZUKU));
            scheduleReconnect();
        }
    };

    public ShizukuController(@NonNull Context context, @Nullable StateListener listener) {
        this(context, listener, null);
    }

    public ShizukuController(@NonNull Context context, @Nullable StateListener listener,
                             @Nullable ClipboardListener clipboardListener) {
        this.listener = listener;
        this.clipboardListener = clipboardListener;
        int userId = ClipboardBridgeProtocol.userIdOf(android.os.Process.myUid());
        serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(
                context.getPackageName(), PrivilegedClipboardService.class.getName()))
                .daemon(false)
                .processNameSuffix("clipboard_bridge")
                .debuggable(BuildConfig.DEBUG)
                .version(ClipboardBridgeProtocol.VERSION)
                // One UserService per Android user: Shizuku may share a tag across users.
                .tag("clipvault.clipboard.v3.u" + userId);
    }

    public void start() {
        if (started) return;
        started = true;
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        tryBind();
    }

    @NonNull
    public BridgeHealth health() {
        return health;
    }

    @Override
    public boolean isReady() {
        IClipboardBridge current = bridge;
        return current != null && current.asBinder().isBinderAlive() && health.isReady();
    }

    @Override
    public boolean isEventDriven() {
        return isReady() && health.isEventDriven();
    }

    /** Called from the capture poller thread. */
    @Nullable
    @Override
    public String readText() {
        IClipboardBridge current = bridge;
        if (current == null || !health.isReady()) return null;
        try {
            int[] result = new int[1];
            String text = current.readText(result);
            int error = result[0];
            if (error != ClipboardBridgeProtocol.OK || health.errorCode != ClipboardBridgeProtocol.OK
                    || transientFailures != 0) {
                mainHandler.post(() -> onReadResult(current, error));
            }
            return text;
        } catch (RemoteException error) {
            mainHandler.post(() -> {
                if (!started) return;
                if (bridge == current) bridge = null;
                publish(BridgeHealth.of(BridgeHealth.State.NO_SHIZUKU));
                scheduleReconnect();
            });
            return null;
        }
    }

    private void onReadResult(@NonNull IClipboardBridge source, int error) {
        if (!started || bridge != source || !health.isReady()) return;
        if (ClipboardBridgeProtocol.isTransientReadFailure(error)) {
            // One odd clip must not stop capture; only a persistent failure degrades the bridge.
            if (++transientFailures < TRANSIENT_FAILURE_LIMIT) return;
        }
        if (ClipboardBridgeProtocol.isApiFailure(error) || ClipboardBridgeProtocol.isTransientReadFailure(error)) {
            transientFailures = 0;
            publish(BridgeHealth.degraded(error));
            scheduleReprobe();
        } else {
            transientFailures = 0;
            publish(health.withError(error));
        }
    }

    private void probe(@NonNull IClipboardBridge candidate) throws RemoteException {
        publish(BridgeHealth.of(BridgeHealth.State.API_PROBING));
        int[] error = new int[1];
        int capabilities = candidate.probeCapabilities(error);
        BridgeHealth next = BridgeHealth.fromProbe(capabilities, error[0]);
        if (next.isEventDriven() && !candidate.registerListener(remoteListener)) next = next.withoutEvents();
        publish(next);
        if (next.isReady()) {
            transientFailures = 0;
            mainHandler.removeCallbacks(reconnect);
            mainHandler.removeCallbacks(reprobe);
            retryDelayMs = INITIAL_RETRY_MS;
        } else {
            scheduleReprobe();
        }
    }

    private void reprobe() {
        IClipboardBridge current = bridge;
        if (!started) return;
        if (current == null || !current.asBinder().isBinderAlive()) {
            bridge = null;
            tryBind();
            return;
        }
        try {
            probe(current);
        } catch (RemoteException error) {
            bridge = null;
            publish(BridgeHealth.of(BridgeHealth.State.NO_SHIZUKU));
            scheduleReconnect();
        }
    }

    private void tryBind() {
        if (!started || binding || bridge != null) return;
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                publish(BridgeHealth.of(BridgeHealth.State.NO_SHIZUKU));
                scheduleReconnect();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                publish(BridgeHealth.of(BridgeHealth.State.PERMISSION_REQUIRED));
                scheduleReconnect();
                return;
            }
            // Clipboard access needs shell only. Root/Sui backends are refused (least privilege).
            if (Shizuku.getUid() != ClipboardBridgeProtocol.SHELL_UID) {
                publish(BridgeHealth.degraded(ClipboardBridgeProtocol.BACKEND_NOT_SHELL));
                scheduleReconnect();
                return;
            }
            binding = true;
            bindAbandoned = false;
            mainHandler.postDelayed(bindTimeout, BIND_TIMEOUT_MS);
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (RuntimeException error) {
            mainHandler.removeCallbacks(bindTimeout);
            binding = false;
            publish(BridgeHealth.of(BridgeHealth.State.NO_SHIZUKU));
            scheduleReconnect();
        }
    }

    /** The UserService never attached (spawn failure, early crash): drop the attempt and retry. */
    private void onBindTimeout() {
        if (!started || !binding) return;
        binding = false;
        bindAbandoned = true;
        try {
            Shizuku.unbindUserService(serviceArgs, connection, true);
        } catch (RuntimeException ignored) {
            // Shizuku may already be stopped.
        }
        publish(BridgeHealth.of(BridgeHealth.State.NO_SHIZUKU));
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        schedule(reconnect);
    }

    private void scheduleReprobe() {
        schedule(reprobe);
    }

    private void schedule(@NonNull Runnable action) {
        if (!started) return;
        mainHandler.removeCallbacks(reconnect);
        mainHandler.removeCallbacks(reprobe);
        mainHandler.postDelayed(action, action == reprobe ? Math.min(retryDelayMs, MAX_REPROBE_MS) : retryDelayMs);
        retryDelayMs = Math.min(retryDelayMs * 2L, MAX_RETRY_MS);
    }

    private void publish(@NonNull BridgeHealth next) {
        if (!started || next.equals(health)) return;
        health = next;
        if (listener != null) listener.onStateChanged(next);
    }

    @Override
    public void close() {
        if (!started) return;
        started = false;
        mainHandler.removeCallbacks(reconnect);
        mainHandler.removeCallbacks(reprobe);
        mainHandler.removeCallbacks(bindTimeout);
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        try {
            IClipboardBridge current = bridge;
            if (current != null) current.unregisterListener(remoteListener);
            Shizuku.unbindUserService(serviceArgs, connection, true);
        } catch (RuntimeException | RemoteException ignored) {
            // Shizuku may already be stopped.
        }
        bridge = null;
        binding = false;
    }

    public static boolean isBinderRunning() {
        try {
            return Shizuku.pingBinder();
        } catch (RuntimeException error) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return isBinderRunning() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** True when the running Shizuku server uses the ADB shell identity ClipVault accepts. */
    public static boolean isShellBackend() {
        try {
            return isBinderRunning() && Shizuku.getUid() == ClipboardBridgeProtocol.SHELL_UID;
        } catch (RuntimeException error) {
            return false;
        }
    }
}
