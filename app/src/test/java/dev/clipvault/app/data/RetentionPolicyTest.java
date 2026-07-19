package dev.clipvault.app.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;

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
}
