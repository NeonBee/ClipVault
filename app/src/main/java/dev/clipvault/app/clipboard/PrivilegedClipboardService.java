package dev.clipvault.app.clipboard;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteCallbackList;
import android.os.RemoteException;

import androidx.annotation.Keep;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Minimal clipboard-only service running under the Shizuku shell/root identity. */
@Keep
public final class PrivilegedClipboardService extends IClipboardBridge.Stub {
    private static final int PROTOCOL_VERSION = 2;
    private static final String LISTENER_DESCRIPTOR = "android.content.IOnPrimaryClipChangedListener";
    private final RemoteCallbackList<IClipboardListener> listeners = new RemoteCallbackList<>();
    private volatile boolean destroyed;
    private volatile boolean systemListenerRegistered;
    private Object clipboardInterface;
    private Object systemListenerProxy;

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
            if (code == 1) {
                data.enforceInterface(LISTENER_DESCRIPTOR);
                dispatchClipboardChanged();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    public PrivilegedClipboardService() {}

    @Keep
    public PrivilegedClipboardService(Context ignored) {}

    @Override
    public String readText() {
        if (destroyed) return null;
        try {
            Object clipboard = clipboard();
            if (clipboard == null) return null;
            Method candidate = findMethod(clipboard, "getPrimaryClip");
            if (candidate == null) return null;
            Object result = candidate.invoke(clipboard, buildArguments(candidate.getParameterTypes(), null));
            if (!(result instanceof ClipData)) return null;
            ClipData data = (ClipData) result;
            if (data.getItemCount() == 0) return null;
            CharSequence text = data.getItemAt(0).getText();
            if (text != null) return text.toString();
            return data.getItemAt(0).getUri() == null ? null : data.getItemAt(0).getUri().toString();
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException | RuntimeException error) {
            return null;
        }
    }

    @Override
    public boolean registerListener(IClipboardListener listener) {
        if (destroyed || listener == null) return false;
        listeners.register(listener);
        if (!systemListenerRegistered) systemListenerRegistered = registerSystemListener();
        return systemListenerRegistered;
    }

    @Override
    public void unregisterListener(IClipboardListener listener) {
        if (listener != null) listeners.unregister(listener);
    }

    @Override
    public int protocolVersion() {
        return PROTOCOL_VERSION;
    }

    @Override
    public void destroy() {
        destroyed = true;
        listeners.kill();
        System.exit(0);
    }

    private boolean registerSystemListener() {
        try {
            Object clipboard = clipboard();
            if (clipboard == null) return false;
            Class<?> stub = Class.forName("android.content.IOnPrimaryClipChangedListener$Stub");
            Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
            systemListenerProxy = asInterface.invoke(null, systemListenerBinder);
            Method candidate = findMethod(clipboard, "addPrimaryClipChangedListener");
            if (candidate == null || systemListenerProxy == null) return false;
            candidate.invoke(clipboard, buildArguments(candidate.getParameterTypes(), systemListenerProxy));
            return true;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException | RuntimeException error) {
            return false;
        }
    }

    private void dispatchClipboardChanged() {
        String text = readText();
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

    private Object clipboard()
            throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Object cached = clipboardInterface;
        if (cached != null) return cached;
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, Context.CLIPBOARD_SERVICE);
        if (binder == null) return null;
        Class<?> stub = Class.forName("android.content.IClipboard$Stub");
        Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
        clipboardInterface = asInterface.invoke(null, binder);
        return clipboardInterface;
    }

    private static Method findMethod(Object target, String name) {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Object[] buildArguments(Class<?>[] parameterTypes, Object listener) {
        Object[] arguments = new Object[parameterTypes.length];
        int stringIndex = 0;
        int integerIndex = 0;
        int userId = Math.max(0, android.os.Process.myUid() / 100_000);
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> type = parameterTypes[index];
            if (listener != null && type.getName().contains("IOnPrimaryClipChangedListener")) {
                arguments[index] = listener;
            } else if (type == String.class) {
                arguments[index] = stringIndex++ == 0 ? "com.android.shell" : null;
            } else if (type == int.class || type == Integer.class) {
                arguments[index] = integerIndex++ == 0 ? userId : 0;
            } else if (type == long.class || type == Long.class) {
                arguments[index] = 0L;
            } else if (type == boolean.class || type == Boolean.class) {
                arguments[index] = false;
            } else {
                arguments[index] = null;
            }
        }
        return arguments;
    }
}
