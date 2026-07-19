package dev.clipvault.app.clipboard;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dev.clipvault.app.BuildConfig;

import rikka.shizuku.Shizuku;

public final class ShizukuController implements AutoCloseable {
    public interface StateListener {
        void onStateChanged(boolean ready);
    }

    private final Shizuku.UserServiceArgs serviceArgs;
    private final StateListener listener;
    private volatile IClipboardBridge bridge;
    private volatile boolean started;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = this::tryBind;
    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        bridge = null;
        notifyState(false);
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            IClipboardBridge candidate = IClipboardBridge.Stub.asInterface(binder);
            try {
                if (candidate != null && candidate.protocolVersion() == 1) {
                    bridge = candidate;
                    notifyState(true);
                    return;
                }
            } catch (RemoteException ignored) {
                // Report the disconnected state below.
            }
            bridge = null;
            notifyState(false);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bridge = null;
            notifyState(false);
        }
    };

    public ShizukuController(@NonNull Context context, @Nullable StateListener listener) {
        this.listener = listener;
        serviceArgs = new Shizuku.UserServiceArgs(new ComponentName(
                context.getPackageName(), PrivilegedClipboardService.class.getName()))
                .daemon(false)
                .processNameSuffix("clipboard_bridge")
                .debuggable(BuildConfig.DEBUG)
                .version(1)
                .tag("clipvault.clipboard.v1");
    }

    public void start() {
        if (started) return;
        started = true;
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
    }

    public boolean isReady() {
        IClipboardBridge current = bridge;
        return current != null && current.asBinder().isBinderAlive();
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
            return null;
        }
    }

    private void tryBind() {
        if (!started) return;
        try {
            if (!Shizuku.pingBinder() || Shizuku.isPreV11()) {
                notifyState(false);
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                notifyState(false);
                return;
            }
            Shizuku.bindUserService(serviceArgs, connection);
        } catch (RuntimeException error) {
            notifyState(false);
        }
    }

    private void notifyState(boolean ready) {
        if (listener != null) listener.onStateChanged(ready);
    }

    @Override
    public void close() {
        if (!started) return;
        started = false;
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        try {
            Shizuku.unbindUserService(serviceArgs, connection, true);
        } catch (RuntimeException ignored) {
            // Shizuku may already be stopped.
        }
        bridge = null;
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
