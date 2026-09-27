package dev.clipvault.app.security;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Sanitized record of why the vault locked (WP-06 session-continuity diagnosis). Each event holds
 * only a reason code, timestamps and device state flags; never clipboard content or key material.
 * Stored newest-first as one preference string, capped at {@link #MAX_EVENTS}.
 */
public final class VaultLockLog {
    public enum Reason {
        /** Process-wide auto-lock timer fired (no ClipVault window started for the configured delay). */
        AUTO_LOCK_TIMEOUT,
        /** ACTION_SCREEN_OFF broadcast. */
        SCREEN_OFF,
        /** MainActivity.onResume found KeyguardManager.isDeviceLocked() true. */
        DEVICE_LOCKED_MAIN,
        /** QuickPasteActivity.onStart found KeyguardManager.isDeviceLocked() true. */
        DEVICE_LOCKED_QUICK_PASTE,
        /** "Lock now" notification action. */
        NOTIFICATION,
        /** Lock button in the app. */
        USER,
        /** The previous process ended while the vault was open (no lockVault call happened). */
        PROCESS_RESTART,
        OTHER
    }

    public static final int MAX_EVENTS = 8;
    /** Tri-state flag values. */
    public static final int UNKNOWN = -1;

    private static final String FIELD = ";";
    private static final String LINE = "\n";

    public static final class Event {
        @NonNull public final Reason reason;
        public final long atMillis;
        /** Milliseconds since the vault was unlocked in this process, or {@link #UNKNOWN}. */
        public final long sinceUnlockMs;
        /** Started ClipVault activities at lock time, or {@link #UNKNOWN}. */
        public final int startedWindows;
        public final int interactive;
        public final int keyguardLocked;
        public final int deviceLocked;
        public final int desktopMode;

        public Event(@NonNull Reason reason, long atMillis, long sinceUnlockMs, int startedWindows,
                     int interactive, int keyguardLocked, int deviceLocked, int desktopMode) {
            this.reason = reason;
            this.atMillis = atMillis;
            this.sinceUnlockMs = sinceUnlockMs;
            this.startedWindows = startedWindows;
            this.interactive = interactive;
            this.keyguardLocked = keyguardLocked;
            this.deviceLocked = deviceLocked;
            this.desktopMode = desktopMode;
        }

        /** One-line diagnostic value, e.g. "DEVICE_LOCKED_MAIN · +5.2s · windows 1 · screen on · keyguard · device locked · DeX". */
        @NonNull
        public String summary() {
            StringBuilder out = new StringBuilder(reason.name());
            if (sinceUnlockMs >= 0) {
                out.append(" · +").append(String.format(Locale.ROOT, "%.1fs", sinceUnlockMs / 1000.0));
            }
            if (startedWindows >= 0) out.append(" · windows ").append(startedWindows);
            flag(out, interactive, "screen on", "screen off");
            flag(out, keyguardLocked, "keyguard", null);
            flag(out, deviceLocked, "device locked", null);
            flag(out, desktopMode, "DeX", null);
            return out.toString();
        }

        private static void flag(StringBuilder out, int value, String whenTrue, @Nullable String whenFalse) {
            if (value == 1) out.append(" · ").append(whenTrue);
            else if (value == 0 && whenFalse != null) out.append(" · ").append(whenFalse);
        }

        @NonNull
        String encode() {
            return reason.name() + FIELD + atMillis + FIELD + sinceUnlockMs + FIELD + startedWindows + FIELD
                    + interactive + FIELD + keyguardLocked + FIELD + deviceLocked + FIELD + desktopMode;
        }

        @Nullable
        static Event decode(@NonNull String line) {
            String[] parts = line.split(FIELD, -1);
            if (parts.length != 8) return null;
            try {
                return new Event(Reason.valueOf(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]),
                        Integer.parseInt(parts[3]), tri(parts[4]), tri(parts[5]), tri(parts[6]), tri(parts[7]));
            } catch (IllegalArgumentException invalid) {
                return null;
            }
        }

        private static int tri(String value) {
            int parsed = Integer.parseInt(value);
            if (parsed < UNKNOWN || parsed > 1) throw new IllegalArgumentException("flag out of range");
            return parsed;
        }
    }

    private VaultLockLog() {
    }

    public static int flag(boolean value) {
        return value ? 1 : 0;
    }

    /** Newest first; unreadable lines (older formats, corruption) are skipped. */
    @NonNull
    public static List<Event> parse(@Nullable String log) {
        if (log == null || log.isEmpty()) return Collections.emptyList();
        List<Event> events = new ArrayList<>();
        for (String line : log.split(LINE)) {
            Event event = Event.decode(line);
            if (event != null) events.add(event);
        }
        return events;
    }

    /** Prepends [event] and keeps at most {@link #MAX_EVENTS} readable events. */
    @NonNull
    public static String append(@Nullable String log, @NonNull Event event) {
        List<Event> events = new ArrayList<>();
        events.add(event);
        events.addAll(parse(log));
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(events.size(), MAX_EVENTS); i++) {
            if (i > 0) out.append(LINE);
            out.append(events.get(i).encode());
        }
        return out.toString();
    }
}
