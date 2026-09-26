package dev.clipvault.app.clipboard;

import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.BACKEND_NOT_SHELL;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.CALLER_MISMATCH;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.CAP_EVENT_LISTENER;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.CAP_PAYLOAD_LIMIT;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.CAP_POLL_FALLBACK;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.CAP_READ;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.CAP_USER_SCOPED;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.CLIPBOARD_SERVICE_MISSING;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.GET_PRIMARY_CLIP_SIGNATURE_UNSUPPORTED;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.INVOCATION_FAILED;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.I_CLIPBOARD_STUB_MISSING;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.LISTENER_SIGNATURE_UNSUPPORTED;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.OK;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.SECURITY_EXCEPTION;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.SERVICE_MANAGER_UNAVAILABLE;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.SHELL_UID;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.TRANSACTION_TOO_LARGE;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.USER_SCOPE_UNSUPPORTED;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.TransactionTooLargeException;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Clipboard-only service running in the Shizuku UserService process. Protocol v3:
 * shell UID only, one bound app UID per service instance, exact hidden API signatures,
 * capability probe, structured error codes and a hard payload limit. Nothing here logs or
 * reports clipboard content.
 */
@Keep
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public final class PrivilegedClipboardService extends IClipboardBridge.Stub {
    private static final String LISTENER_DESCRIPTOR = "android.content.IOnPrimaryClipChangedListener";
    private static final int UNBOUND = -1;

    private final RemoteCallbackList<IClipboardListener> listeners = new RemoteCallbackList<>();
    private final Object apiLock = new Object();
    private final int backendError;
    private volatile boolean destroyed;
    private volatile int boundUid = UNBOUND;
    private volatile int lastError = OK;

    // Guarded by apiLock.
    private boolean resolved;
    private int resolveError = OK;
    private Object clipboard;
    private HiddenClipboardApi.Binding readBinding;
    private HiddenClipboardApi.Binding listenerBinding;
    private boolean systemListenerRegistered;

    private final Binder systemListenerBinder = new Binder() {
        {
            attachInterface(null, LISTENER_DESCRIPTOR);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(LISTENER_DESCRIPTOR);
                return true;
            }
            // IOnPrimaryClipChangedListener has exactly one oneway method, transaction 1.
            if (code == FIRST_CALL_TRANSACTION) {
                data.enforceInterface(LISTENER_DESCRIPTOR);
                dispatchClipboardChanged();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    public PrivilegedClipboardService() {
        backendError = android.os.Process.myUid() == SHELL_UID ? OK : BACKEND_NOT_SHELL;
    }

    @Keep
    public PrivilegedClipboardService(Context ignored) {
        this();
    }

    @Override
    public int protocolVersion() {
        return ClipboardBridgeProtocol.VERSION;
    }

    @Override
    public String readText(int[] error) {
        int admission = admit(Binder.getCallingUid());
        if (admission != OK) {
            report(error, admission);
            return null;
        }
        ReadResult result = readForBoundUser();
        report(error, result.error);
        return result.text;
    }

    @Override
    public int probeCapabilities(int[] error) {
        int admission = admit(Binder.getCallingUid());
        if (admission != OK) {
            report(error, admission);
            return 0;
        }
        synchronized (apiLock) {
            int capabilities = CAP_PAYLOAD_LIMIT;
            int listenerError = ensureSystemListener();
            if (systemListenerRegistered) capabilities |= CAP_EVENT_LISTENER;
            int readError = readForBoundUser().error;
            // An oversized current clip still proves that the API works.
            if (readError == OK || readError == TRANSACTION_TOO_LARGE) capabilities |= CAP_READ | CAP_POLL_FALLBACK;
            if (readBinding != null && readBinding.isUserScoped()) capabilities |= CAP_USER_SCOPED;
            int probeError = readError != OK ? readError : listenerError;
            lastError = probeError;
            report(error, probeError);
            return capabilities;
        }
    }

    private static void report(@Nullable int[] error, int code) {
        if (error != null && error.length > 0) error[0] = code;
    }

    /** One read and its own error code; never shared between threads. */
    private static final class ReadResult {
        @Nullable final String text;
        final int error;

        ReadResult(@Nullable String text, int error) {
            this.text = text;
            this.error = error;
        }
    }

    @Override
    public int lastErrorCode() {
        int admission = admit(Binder.getCallingUid());
        return admission != OK ? admission : lastError;
    }

    @Override
    public boolean registerListener(IClipboardListener listener) {
        if (listener == null || admit(Binder.getCallingUid()) != OK) return false;
        listeners.register(listener);
        synchronized (apiLock) {
            int error = ensureSystemListener();
            if (error != OK) lastError = error;
            return systemListenerRegistered;
        }
    }

    @Override
    public void unregisterListener(IClipboardListener listener) {
        if (listener == null || admit(Binder.getCallingUid()) != OK) return;
        listeners.unregister(listener);
    }

    @Override
    public void destroy() {
        destroyed = true;
        listeners.kill();
        System.exit(0);
    }

    /**
     * Shell-only backend and a single app UID per instance. The first caller binds the service;
     * the per-user tag on the client side keeps each Android user on its own instance.
     */
    private int admit(int callingUid) {
        if (destroyed) return INVOCATION_FAILED;
        if (backendError != OK) return backendError;
        if (boundUid == UNBOUND) {
            synchronized (apiLock) {
                if (boundUid == UNBOUND) boundUid = callingUid;
            }
        }
        return boundUid == callingUid ? OK : CALLER_MISMATCH;
    }

    /** Runs for the stored caller user, also on system_server callback threads. */
    @NonNull
    private ReadResult readForBoundUser() {
        synchronized (apiLock) {
            ReadResult result = readLocked();
            lastError = result.error;
            return result;
        }
    }

    @NonNull
    private ReadResult readLocked() {
        int error = ensureResolved();
        if (error != OK) return new ReadResult(null, error);
        int userId = ClipboardBridgeProtocol.userIdOf(boundUid);
        if (!readBinding.isUserScoped() && userId != 0) return new ReadResult(null, USER_SCOPE_UNSUPPORTED);
        try {
            String text = extractText(readBinding.invoke(clipboard, userId, null));
            if (text != null && ClipboardBridgeProtocol.exceedsPayloadLimit(text)) {
                // Reject instead of truncating: a stored clip must equal what the user copied.
                return new ReadResult(null, TRANSACTION_TOO_LARGE);
            }
            return new ReadResult(text, OK);
        } catch (InvocationTargetException error2) {
            return new ReadResult(null, classify(error2.getCause()));
        } catch (IllegalAccessException | RuntimeException error2) {
            return new ReadResult(null, classify(error2));
        }
    }

    @Nullable
    private static String extractText(@Nullable Object result) {
        if (!(result instanceof ClipData)) return null;
        ClipData data = (ClipData) result;
        if (data.getItemCount() == 0) return null;
        ClipData.Item item = data.getItemAt(0);
        CharSequence text = item.getText();
        if (text != null) return text.toString();
        return item.getUri() == null ? null : item.getUri().toString();
    }

    private int ensureSystemListener() {
        if (systemListenerRegistered) return OK;
        int error = ensureResolved();
        if (error != OK) return error;
        if (listenerBinding == null) return LISTENER_SIGNATURE_UNSUPPORTED;
        int userId = ClipboardBridgeProtocol.userIdOf(boundUid);
        if (!listenerBinding.isUserScoped() && userId != 0) return USER_SCOPE_UNSUPPORTED;
        try {
            Class<?> stub = Class.forName("android.content.IOnPrimaryClipChangedListener$Stub");
            Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            Object proxy = asInterface.invoke(null, systemListenerBinder);
            if (proxy == null) return LISTENER_SIGNATURE_UNSUPPORTED;
            listenerBinding.invoke(clipboard, userId, proxy);
            systemListenerRegistered = true;
            return OK;
        } catch (ClassNotFoundException | NoSuchMethodException error2) {
            return LISTENER_SIGNATURE_UNSUPPORTED;
        } catch (InvocationTargetException error2) {
            return classify(error2.getCause());
        } catch (IllegalAccessException | RuntimeException error2) {
            return classify(error2);
        }
    }

    /** Resolves ServiceManager, IClipboard and the exact signatures once per service instance. */
    private int ensureResolved() {
        if (resolved) return resolveError;
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            Method getService = serviceManager.getDeclaredMethod("getService", String.class);
            IBinder binder = (IBinder) getService.invoke(null, Context.CLIPBOARD_SERVICE);
            // Not cached: the clipboard service may still be starting after boot.
            if (binder == null) return CLIPBOARD_SERVICE_MISSING;
            Class<?> api;
            try {
                api = Class.forName("android.content.IClipboard");
                Method asInterface = Class.forName("android.content.IClipboard$Stub")
                        .getDeclaredMethod("asInterface", IBinder.class);
                clipboard = asInterface.invoke(null, binder);
            } catch (ClassNotFoundException | NoSuchMethodException error) {
                return finishResolve(I_CLIPBOARD_STUB_MISSING);
            }
            if (clipboard == null) return finishResolve(I_CLIPBOARD_STUB_MISSING);
            readBinding = HiddenClipboardApi.resolve(api, HiddenClipboardApi.READ_SIGNATURES);
            listenerBinding = HiddenClipboardApi.resolve(api, HiddenClipboardApi.LISTENER_SIGNATURES);
            return finishResolve(readBinding == null ? GET_PRIMARY_CLIP_SIGNATURE_UNSUPPORTED : OK);
        } catch (ClassNotFoundException | NoSuchMethodException error) {
            return finishResolve(SERVICE_MANAGER_UNAVAILABLE);
        } catch (InvocationTargetException | IllegalAccessException | RuntimeException error) {
            return SERVICE_MANAGER_UNAVAILABLE;
        }
    }

    private int finishResolve(int error) {
        resolved = true;
        resolveError = error;
        return error;
    }

    private static int classify(@Nullable Throwable error) {
        if (error instanceof SecurityException) return SECURITY_EXCEPTION;
        if (error instanceof TransactionTooLargeException) return TRANSACTION_TOO_LARGE;
        return INVOCATION_FAILED;
    }

    private void dispatchClipboardChanged() {
        if (destroyed) return;
        String text = readForBoundUser().text;
        int count = listeners.beginBroadcast();
        try {
            for (int index = 0; index < count; index++) {
                try {
                    listeners.getBroadcastItem(index).onClipboardChanged(text);
                } catch (RemoteException ignored) {
                    // RemoteCallbackList removes dead clients.
                }
            }
        } finally {
            listeners.finishBroadcast();
        }
    }
}
