package dev.clipvault.app.clipboard;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.os.IBinder;

import androidx.annotation.Keep;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Runs inside Shizuku's shell/root-identity UserService process. */
@Keep
public final class PrivilegedClipboardService extends IClipboardBridge.Stub {
    private static final int PROTOCOL_VERSION = 1;
    private volatile boolean destroyed;

    public PrivilegedClipboardService() {}

    @Keep
    public PrivilegedClipboardService(Context ignored) {}

    @Override
    public String readText() {
        if (destroyed) return null;
        try {
            Object clipboard = getClipboardInterface();
            if (clipboard == null) return null;
            Method candidate = null;
            for (Method method : clipboard.getClass().getMethods()) {
                if (method.getName().equals("getPrimaryClip")) {
                    candidate = method;
                    break;
                }
            }
            if (candidate == null) return null;
            candidate.setAccessible(true);
            Object result = candidate.invoke(clipboard, buildArguments(candidate.getParameterTypes()));
            if (!(result instanceof ClipData)) return null;
            ClipData data = (ClipData) result;
            if (data.getItemCount() == 0) return null;
            CharSequence text = data.getItemAt(0).getText();
            if (text != null) return text.toString();
            if (data.getItemAt(0).getUri() != null) return data.getItemAt(0).getUri().toString();
            return null;
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                 InvocationTargetException | RuntimeException error) {
            return null;
        }
    }

    @Override
    public int protocolVersion() {
        return PROTOCOL_VERSION;
    }

    @Override
    public void destroy() {
        destroyed = true;
        System.exit(0);
    }

    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    private static Object getClipboardInterface()
            throws ClassNotFoundException, NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        IBinder binder = (IBinder) getService.invoke(null, Context.CLIPBOARD_SERVICE);
        if (binder == null) return null;

        Class<?> stub = Class.forName("android.content.IClipboard$Stub");
        Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
        return asInterface.invoke(null, binder);
    }

    private static Object[] buildArguments(Class<?>[] parameterTypes) {
        Object[] arguments = new Object[parameterTypes.length];
        int stringIndex = 0;
        int integerIndex = 0;
        int userId = Math.max(0, android.os.Process.myUid() / 100_000);
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> type = parameterTypes[index];
            if (type == String.class) {
                arguments[index] = stringIndex++ == 0 ? "com.android.shell" : null;
            } else if (type == int.class || type == Integer.class) {
                // First integer is userId; newer Android versions append deviceId.
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
