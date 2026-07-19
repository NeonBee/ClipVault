package dev.clipvault.app.data;

import java.util.Calendar;

public final class RetentionPolicy {
    public static final int FOREVER = -1;
    public static final int ONE_MONTH = 1;
    public static final int THREE_MONTHS = 3;
    public static final int SIX_MONTHS = 6;

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

    public static boolean shouldKeep(long createdAt, int months, long now) {
        return months == FOREVER || createdAt >= cutoffForMonths(months, now);
    }
}
