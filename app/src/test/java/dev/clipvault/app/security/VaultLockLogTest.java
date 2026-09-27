package dev.clipvault.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class VaultLockLogTest {
    private static VaultLockLog.Event event(VaultLockLog.Reason reason, long at) {
        return new VaultLockLog.Event(reason, at, 5_200L, 1, 1, 1, 1, 1);
    }

    @Test
    public void roundTripKeepsEveryField() {
        String log = VaultLockLog.append("", new VaultLockLog.Event(
                VaultLockLog.Reason.DEVICE_LOCKED_MAIN, 1_000L, 5_200L, 1, 1, 0, 1, VaultLockLog.UNKNOWN));
        VaultLockLog.Event parsed = VaultLockLog.parse(log).get(0);
        assertEquals(VaultLockLog.Reason.DEVICE_LOCKED_MAIN, parsed.reason);
        assertEquals(1_000L, parsed.atMillis);
        assertEquals(5_200L, parsed.sinceUnlockMs);
        assertEquals(1, parsed.startedWindows);
        assertEquals(1, parsed.interactive);
        assertEquals(0, parsed.keyguardLocked);
        assertEquals(1, parsed.deviceLocked);
        assertEquals(VaultLockLog.UNKNOWN, parsed.desktopMode);
    }

    @Test
    public void newestFirstAndCappedAtMaxEvents() {
        String log = "";
        for (int i = 0; i < VaultLockLog.MAX_EVENTS + 3; i++) {
            log = VaultLockLog.append(log, event(VaultLockLog.Reason.AUTO_LOCK_TIMEOUT, i));
        }
        List<VaultLockLog.Event> events = VaultLockLog.parse(log);
        assertEquals(VaultLockLog.MAX_EVENTS, events.size());
        assertEquals(VaultLockLog.MAX_EVENTS + 2, events.get(0).atMillis);
        assertEquals(3, events.get(events.size() - 1).atMillis);
    }

    @Test
    public void unreadableLinesAreDroppedNotFatal() {
        String log = "garbage\nNOT_A_REASON;1;2;3;1;1;1;1\nSCREEN_OFF;9;-1;0;0;1;1;7\n"
                + "SCREEN_OFF;10;-1;0;0;1;1;0";
        List<VaultLockLog.Event> events = VaultLockLog.parse(log);
        assertEquals(1, events.size());
        assertEquals(10L, events.get(0).atMillis);
        assertEquals(0, VaultLockLog.parse(null).size());
        // Appending to a damaged log also sheds the damage.
        assertEquals(2, VaultLockLog.parse(VaultLockLog.append(log, event(VaultLockLog.Reason.USER, 11))).size());
    }

    @Test
    public void summaryNamesReasonTimingAndDeviceState() {
        assertEquals("DEVICE_LOCKED_MAIN · +5.2s · windows 1 · interactive · keyguard · device locked · DeX",
                event(VaultLockLog.Reason.DEVICE_LOCKED_MAIN, 0).summary());
        String unknown = new VaultLockLog.Event(VaultLockLog.Reason.PROCESS_RESTART, 0, VaultLockLog.UNKNOWN,
                VaultLockLog.UNKNOWN, 0, 0, 0, VaultLockLog.UNKNOWN).summary();
        assertEquals("PROCESS_RESTART · non-interactive", unknown);
    }

    @Test
    public void summaryNeverContainsSeparatorsThatBreakStorage() {
        String encoded = VaultLockLog.append("", event(VaultLockLog.Reason.OTHER, 1));
        assertTrue(!encoded.contains("\n"));
    }
}
