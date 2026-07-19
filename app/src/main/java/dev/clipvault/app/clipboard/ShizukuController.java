package dev.clipvault.app.clipboard;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.clipvault.app.BuildConfig;

import rikka.shizuku.Shizuku;

public final class ShizukuController implements ClipboardBridge, AutoCloseable {
    public interface StateListener {
        void onStateChanged(boolean ready);
    }

    public interface ClipboardListener {
        void onClipboardChanged(@Nullable String text);
    }

    private final Shizuku.UserServiceArgs serviceArgs;
    private final StateListener listener;
    private final ClipboardListener clipboardListener;
    private volatile IClipboardBridge bridge;
    private volatile boolean started;
    private volatile boolean eventDriven;
    private volatile boolean binding;
    private final Handler retryHandler = new Handler(Looper.getMainLooper());
    private long retryDelayMs = 1_000L;
    private final Runnable reconnect = this::tryBind;

    private final IClipboardListener remoteListener = new IClipboardListener.Stub() {
        @Override
        public void onClipboardChanged(String text) {
            if (clipboardListener != null) clipboardListener.onClipboardChanged(text);
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::tryBind;
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        bridge = null;
        eventDriven = false;
        binding = false;
        notifyState(false);
        scheduleReconnect();
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            binding = false;
            IClipboardBridge candidate = IClipboardBridge.Stub.asInterface(binder);
            try {
                if (candidate != null && candidate.protocolVersion() == 2) {
                    bridge = candidate;
                    eventDriven = candidate.registerListener(remoteListener);
                    retryHandler.removeCallbacks(reconnect);
                    retryDelayMs = 1_000L;
                    notifyState(true);
                    return;
                }
            } catch (RemoteException ignored) {
                // Report the disconnected state below.
            }
            bridge = null;
            notifyState(false);
            scheduleReconnect();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridge = null;
            eventDriven = false;
            binding = false;
            notifyState(false);
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
        serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(
                context.getPackageName(), PrivilegedClipboardService.class.getName()))
                .daemon(false)
                .processNameSuffix("clipboard_bridge")
                .debuggable(BuildConfig.DEBUG)
                .version(2)
                .tag("clipvault.clipboard.v2");
    }

    public void start() {
        if (started) return;
        started = true;
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        tryBind();
    }

    public boolean isReady() {
        IClipboardBridge current = bridge;
        return current != null && current.asBinder().isBinderAlive();
    }

    public boolean isEventDriven() {
        return isReady() && eventDriven;
    }

    @Nullable
    public String readText() {
        IClipboardBridge current = bridge;
        if (current == null) return null;
        try {
            return current.readText();
        } catch (RemoteException error) {
            bridge = null;
            notifyState(false);
            scheduleReconnect();
            return null;
        }
    }

    private void tryBind() {
        if (!started || isReady() || binding) return;
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                notifyState(false);
                scheduleReconnect();
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                notifyState(false);
                scheduleReconnect();
                return;
            }
            binding = true;
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (RuntimeException error) {
            binding = false;
            notifyState(false);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!started) return;
        retryHandler.removeCallbacks(reconnect);
        retryHandler.postDelayed(reconnect, retryDelayMs);
        retryDelayMs = Math.min(retryDelayMs * 2L, 60_000L);
    }

    private void notifyState(boolean ready) {
        if (listener != null) listener.onStateChanged(ready);
    }

    @Override
    public void close() {
        if (!started) return;
        started = false;
        retryHandler.removeCallbacks(reconnect);
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
        eventDriven = false;
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
}
