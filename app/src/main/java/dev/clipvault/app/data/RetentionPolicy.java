package dev.clipvault.app.data;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public final class RetentionPolicy {
    public static final int FOREVER = -1;
    public static final int ONE_MONTH = 1;
    public static final int THREE_MONTHS = 3;
    public static final int SIX_MONTHS = 6;
    public static final int MIN_CUSTOM_DAYS = 7;
    public static final int MAX_CUSTOM_DAYS = 365;
    public static final int TRASH_DAYS = 30;

    private RetentionPolicy() {}

    public static long cutoffForMonths(int months, long now) {
        if (months == FOREVER) return Long.MIN_VALUE;
        if (months != ONE_MONTH && months != THREE_MONTHS && months != SIX_MONTHS) {
            throw new IllegalArgumentException("Unsupported retention period: " + months);
        }
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        calendar.add(Calendar.MONTH, -months);
        return calendar.getTimeInMillis();
    }

    public static long cutoffForCustomDays(int days, long now) {
        if (days < MIN_CUSTOM_DAYS || days > MAX_CUSTOM_DAYS) {
            throw new IllegalArgumentException("Custom retention must be 7..365 days");
        }
        return now - TimeUnit.DAYS.toMillis(days);
    }

    public static long trashCutoff(long now) {
        return now - TimeUnit.DAYS.toMillis(TRASH_DAYS);
    }

    public static boolean shouldKeep(long lastCapturedAt, int months, long now) {
        return months == FOREVER || lastCapturedAt >= cutoffForMonths(months, now);
    }
}
