package dev.clipvault.app.clipboard;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Exact-signature table for the hidden {@code android.content.IClipboard} methods ClipVault uses.
 * A method is only called when its name, fully-qualified return type and full parameter list match
 * a known AOSP variant, so a vendor overload or a reordered parameter list fails closed instead of receiving
 * guessed arguments. Pure Java so the resolution rules run in JVM unit tests.
 *
 * <p>Sources: frameworks/base core/java/android/content/IClipboard.aidl at android-7.0.0_r1
 * through android-15.0.0_r20, android16-release and main (checked 2026-09-26).
 */
final class HiddenClipboardApi {
    static final String SHELL_PACKAGE = "com.android.shell";
    /** android.content.Context.DEVICE_ID_DEFAULT. */
    static final int DEFAULT_DEVICE_ID = 0;
    enum Param { PACKAGE, ATTRIBUTION_TAG, USER_ID, DEVICE_ID, LISTENER }

    enum Returns { CLIP_DATA, VOID }

    /** Fully-qualified framework type names; injectable so JVM tests can use stand-in types. */
    static final class Types {
        static final Types AOSP = new Types("android.content.ClipData",
                "android.content.IOnPrimaryClipChangedListener");

        @NonNull final String clipData;
        @NonNull final String listener;

        Types(@NonNull String clipData, @NonNull String listener) {
            this.clipData = clipData;
            this.listener = listener;
        }
    }

    static final class Signature {
        @NonNull final String label;
        @NonNull final String name;
        @NonNull final Returns returnType;
        @NonNull final Param[] params;

        Signature(@NonNull String label, @NonNull String name, @NonNull Returns returnType, @NonNull Param... params) {
            this.label = label;
            this.name = name;
            this.returnType = returnType;
            this.params = params;
        }

        boolean matches(@NonNull Method method, @NonNull Types typeNames) {
            if (!method.getName().equals(name)) return false;
            Class<?> returned = method.getReturnType();
            boolean returnMatches = returnType == Returns.VOID
                    ? returned == void.class : returned.getName().equals(typeNames.clipData);
            if (!returnMatches) return false;
            Class<?>[] types = method.getParameterTypes();
            if (types.length != params.length) return false;
            for (int index = 0; index < types.length; index++) {
                if (!accepts(params[index], types[index], typeNames)) return false;
            }
            return true;
        }

        boolean isUserScoped() {
            for (Param param : params) if (param == Param.USER_ID) return true;
            return false;
        }

        private static boolean accepts(@NonNull Param param, @NonNull Class<?> type, @NonNull Types typeNames) {
            switch (param) {
                case PACKAGE:
                case ATTRIBUTION_TAG:
                    return type == String.class;
                case USER_ID:
                case DEVICE_ID:
                    return type == int.class;
                case LISTENER:
                    return type.getName().equals(typeNames.listener);
                default:
                    return false;
            }
        }
    }

    /** Newest first. Android 13 changed the list during its lifetime (r1 vs r50). */
    static final Signature[] READ_SIGNATURES = {
            new Signature("api34+", "getPrimaryClip", Returns.CLIP_DATA,
                    Param.PACKAGE, Param.ATTRIBUTION_TAG, Param.USER_ID, Param.DEVICE_ID),
            new Signature("api33-late", "getPrimaryClip", Returns.CLIP_DATA,
                    Param.PACKAGE, Param.ATTRIBUTION_TAG, Param.USER_ID),
            new Signature("api29-33", "getPrimaryClip", Returns.CLIP_DATA,
                    Param.PACKAGE, Param.USER_ID),
            new Signature("api24-28", "getPrimaryClip", Returns.CLIP_DATA,
                    Param.PACKAGE),
    };

    static final Signature[] LISTENER_SIGNATURES = {
            new Signature("api34+", "addPrimaryClipChangedListener", Returns.VOID,
                    Param.LISTENER, Param.PACKAGE, Param.ATTRIBUTION_TAG, Param.USER_ID, Param.DEVICE_ID),
            new Signature("api33-late", "addPrimaryClipChangedListener", Returns.VOID,
                    Param.LISTENER, Param.PACKAGE, Param.ATTRIBUTION_TAG, Param.USER_ID),
            new Signature("api29-33", "addPrimaryClipChangedListener", Returns.VOID,
                    Param.LISTENER, Param.PACKAGE, Param.USER_ID),
            new Signature("api24-28", "addPrimaryClipChangedListener", Returns.VOID,
                    Param.LISTENER, Param.PACKAGE),
    };

    static final class Binding {
        @NonNull final Signature signature;
        @NonNull final Method method;

        Binding(@NonNull Signature signature, @NonNull Method method) {
            this.signature = signature;
            this.method = method;
        }

        boolean isUserScoped() {
            return signature.isUserScoped();
        }

        @NonNull
        Object[] arguments(int userId, @Nullable Object listener) {
            Object[] arguments = new Object[signature.params.length];
            for (int index = 0; index < arguments.length; index++) {
                switch (signature.params[index]) {
                    case PACKAGE: arguments[index] = SHELL_PACKAGE; break;
                    case ATTRIBUTION_TAG: arguments[index] = null; break;
                    case USER_ID: arguments[index] = userId; break;
                    case DEVICE_ID: arguments[index] = DEFAULT_DEVICE_ID; break;
                    case LISTENER: arguments[index] = listener; break;
                    default: throw new IllegalStateException("Unhandled parameter");
                }
            }
            return arguments;
        }

        @Nullable
        Object invoke(@NonNull Object target, int userId, @Nullable Object listener)
                throws IllegalAccessException, InvocationTargetException {
            return method.invoke(target, arguments(userId, listener));
        }
    }

    private HiddenClipboardApi() {}

    /**
     * Returns the first table entry that the API class implements exactly. The table order, not
     * {@link Class#getMethods()} order, decides between overloads.
     */
    @Nullable
    static Binding resolve(@NonNull Class<?> api, @NonNull Signature[] table) {
        return resolve(api, table, Types.AOSP);
    }

    @Nullable
    static Binding resolve(@NonNull Class<?> api, @NonNull Signature[] table, @NonNull Types typeNames) {
        Method[] methods = api.getMethods();
        Arrays.sort(methods, (left, right) -> left.toGenericString().compareTo(right.toGenericString()));
        for (Signature signature : table) {
            for (Method method : methods) {
                if (signature.matches(method, typeNames)) return new Binding(signature, method);
            }
        }
        return null;
    }
}
