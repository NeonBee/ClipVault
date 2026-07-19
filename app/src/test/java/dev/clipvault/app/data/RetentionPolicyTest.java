package dev.clipvault.app.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.concurrent.TimeUnit;

public class RetentionPolicyTest {
    @Test
    public void foreverKeepsOldItems() {
        assertTrue(RetentionPolicy.shouldKeep(0L, RetentionPolicy.FOREVER, System.currentTimeMillis()));
    }

    @Test
    public void oneMonthRejectsItemsOlderThanCalendarMonth() {
        Calendar now = Calendar.getInstance();
        now.set(2026, Calendar.JULY, 19, 12, 0, 0);
        Calendar old = (Calendar) now.clone();
        old.add(Calendar.MONTH, -1);
        old.add(Calendar.MINUTE, -1);
        assertFalse(RetentionPolicy.shouldKeep(old.getTimeInMillis(), RetentionPolicy.ONE_MONTH, now.getTimeInMillis()));
    }

    @Test
    public void customRetentionUsesExactDays() {
        long now = 10_000_000_000L;
        long cutoff = RetentionPolicy.cutoffForCustomDays(30, now);
        assertTrue(cutoff == now - TimeUnit.DAYS.toMillis(30));
    }

    @Test(expected = IllegalArgumentException.class)
    public void customRetentionRejectsUnsafeRange() {
        RetentionPolicy.cutoffForCustomDays(2, System.currentTimeMillis());
    }

    @Test
    public void trashCutoffIsThirtyDays() {
        long now = 20_000_000_000L;
        assertTrue(RetentionPolicy.trashCutoff(now) == now - TimeUnit.DAYS.toMillis(30));
    }
}
