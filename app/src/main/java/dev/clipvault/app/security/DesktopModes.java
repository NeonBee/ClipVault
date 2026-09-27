package dev.clipvault.app.security;

import android.content.res.Configuration;

import androidx.annotation.NonNull;

/** Samsung DeX detection, diagnostics only. DeX is never an authentication factor (design §20.10). */
public final class DesktopModes {
    private DesktopModes() {
    }

    /** 1 = Samsung DeX desktop mode, 0 = not DeX, -1 = unknown (non-Samsung framework or reflection failure). */
    public static int samsungDex(@NonNull Configuration configuration) {
        try {
            Class<?> type = configuration.getClass();
            int enabled = type.getField("SEM_DESKTOP_MODE_ENABLED").getInt(null);
            int current = type.getField("semDesktopModeEnabled").getInt(configuration);
            return current == enabled ? 1 : 0;
        } catch (ReflectiveOperationException | RuntimeException unsupported) {
            return VaultLockLog.UNKNOWN;
        }
    }
}
