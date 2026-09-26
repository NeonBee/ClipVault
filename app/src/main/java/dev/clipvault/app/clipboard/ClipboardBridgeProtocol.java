package dev.clipvault.app.clipboard;

import androidx.annotation.NonNull;

/**
 * Contract shared by the app process and the Shizuku UserService. Values only describe bridge
 * state; clipboard content never appears in capabilities, error codes or diagnostics.
 */
public final class ClipboardBridgeProtocol {
    public static final int VERSION = 3;

    /** Android shell identity used by ADB-started Shizuku. Clipboard access needs nothing more. */
    public static final int SHELL_UID = 2000;
    /** android.os.UserHandle.PER_USER_RANGE; stable since multi-user support. */
    public static final int PER_USER_RANGE = 100_000;
    /**
     * Largest text accepted across the bridge. UTF-16 parcels use two bytes per char, so this
     * stays well below the ~1 MB process-wide Binder transaction buffer.
     */
    public static final int MAX_BRIDGE_CHARS = 128_000;

    public static final int CAP_READ = 1;
    public static final int CAP_EVENT_LISTENER = 1 << 1;
    public static final int CAP_POLL_FALLBACK = 1 << 2;
    public static final int CAP_USER_SCOPED = 1 << 3;
    public static final int CAP_PAYLOAD_LIMIT = 1 << 4;

    public static final int OK = 0;
    public static final int SERVICE_MANAGER_UNAVAILABLE = 1;
    public static final int CLIPBOARD_SERVICE_MISSING = 2;
    public static final int I_CLIPBOARD_STUB_MISSING = 3;
    public static final int GET_PRIMARY_CLIP_SIGNATURE_UNSUPPORTED = 4;
    public static final int LISTENER_SIGNATURE_UNSUPPORTED = 5;
    public static final int SECURITY_EXCEPTION = 6;
    public static final int INVOCATION_FAILED = 7;
    /** Clipboard text over {@link #MAX_BRIDGE_CHARS}, or a real TransactionTooLargeException. */
    public static final int TRANSACTION_TOO_LARGE = 8;
    public static final int BACKEND_NOT_SHELL = 9;
    /** A second app UID tried to use a UserService already bound to another caller. */
    public static final int CALLER_MISMATCH = 10;
    /** The resolved hidden API has no userId parameter but the caller is not Android user 0. */
    public static final int USER_SCOPE_UNSUPPORTED = 11;
    public static final int PROTOCOL_MISMATCH = 12;

    private static final String[] ERROR_NAMES = {
            "OK",
            "SERVICE_MANAGER_UNAVAILABLE",
            "CLIPBOARD_SERVICE_MISSING",
            "I_CLIPBOARD_STUB_MISSING",
            "GET_PRIMARY_CLIP_SIGNATURE_UNSUPPORTED",
            "LISTENER_SIGNATURE_UNSUPPORTED",
            "SECURITY_EXCEPTION",
            "INVOCATION_FAILED",
            "TRANSACTION_TOO_LARGE",
            "BACKEND_NOT_SHELL",
            "CALLER_MISMATCH",
            "USER_SCOPE_UNSUPPORTED",
            "PROTOCOL_MISMATCH",
    };

    private ClipboardBridgeProtocol() {}

    @NonNull
    public static String errorName(int code) {
        return code >= 0 && code < ERROR_NAMES.length ? ERROR_NAMES[code] : "UNKNOWN_" + code;
    }

    public static int userIdOf(int uid) {
        return uid / PER_USER_RANGE;
    }

    public static boolean exceedsPayloadLimit(@NonNull CharSequence text) {
        return text.length() > MAX_BRIDGE_CHARS;
    }

    /** Errors that mean the hidden clipboard API itself is unusable, as opposed to one bad clip. */
    public static boolean isApiFailure(int code) {
        return code != OK && code != TRANSACTION_TOO_LARGE;
    }
}
