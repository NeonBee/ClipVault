package dev.clipvault.app.security;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class VaultAutoLockTest {
    private final FakeScheduler scheduler = new FakeScheduler();
    private final AtomicBoolean unlocked = new AtomicBoolean(true);
    private final AtomicInteger locks = new AtomicInteger();
    private long configuredDelay = 30_000L;
    private VaultAutoLock autoLock;

    @Before
    public void setUp() {
        autoLock = new VaultAutoLock(scheduler, () -> configuredDelay, unlocked::get, locks::incrementAndGet);
    }

    @Test
    public void quickPasteOverMainWindowDoesNotLockTheVaultUnderneath() {
        autoLock.onActivityStarted();          // MainActivity
        autoLock.onActivityStarted();          // QuickPasteActivity on top
        autoLock.onActivityStopped(false);     // MainActivity hidden by QuickPaste
        assertFalse(scheduler.pending());

        autoLock.onActivityStopped(false);     // QuickPaste closed, nothing visible
        assertTrue(scheduler.pending());
        assertEquals(30_000L, scheduler.delay);
        scheduler.fire();
        assertEquals(1, locks.get());
    }

    @Test
    public void vaultUnlockedFromQuickPasteAloneStillAutoLocks() {
        autoLock.onActivityStarted();
        autoLock.onActivityStopped(false);
        assertTrue(scheduler.pending());
    }

    @Test
    public void unlockCompletingAfterEveryWindowStoppedStillArmsTheTimer() {
        // Review PR #11: biometric success, window stops while SQLCipher is still opening (vault locked
        // at stop, nothing armed), then the asynchronous open completes.
        unlocked.set(false);
        autoLock.onActivityStarted();
        autoLock.onActivityStopped(false);
        assertFalse(scheduler.pending());

        unlocked.set(true);
        autoLock.onVaultUnlocked();
        assertTrue(scheduler.pending());
        assertEquals(30_000L, scheduler.delay);
        scheduler.fire();
        assertEquals(1, locks.get());
    }

    @Test
    public void unlockCompletingWhileAWindowIsVisibleWaitsForItToStop() {
        autoLock.onActivityStarted();
        autoLock.onVaultUnlocked();
        assertFalse(scheduler.pending());
        autoLock.onActivityStopped(false);
        assertTrue(scheduler.pending());
    }

    @Test
    public void unlockHookDoesNothingIfTheVaultIsAlreadyLockedAgain() {
        unlocked.set(false);
        autoLock.onVaultUnlocked();
        assertFalse(scheduler.pending());
    }

    @Test
    public void startingAnyWindowCancelsThePendingLock() {
        autoLock.onActivityStarted();
        autoLock.onActivityStopped(false);
        autoLock.onActivityStarted();
        assertFalse(scheduler.pending());
    }

    @Test
    public void configurationChangeAndLockedVaultDoNotArmTheTimer() {
        autoLock.onActivityStarted();
        autoLock.onActivityStopped(true);
        assertFalse(scheduler.pending());

        unlocked.set(false);
        autoLock.onActivityStarted();
        autoLock.onActivityStopped(false);
        assertFalse(scheduler.pending());
    }

    @Test
    public void lockingByAnotherPathCancelsTheStaleTimer() {
        autoLock.onActivityStarted();
        autoLock.onActivityStopped(false);
        autoLock.onVaultLocked();
        assertFalse(scheduler.pending());
    }

    @Test
    public void unbalancedStopNeverGoesNegative() {
        autoLock.onActivityStopped(false);
        assertEquals(0, autoLock.startedActivities());
        autoLock.onActivityStarted();
        assertEquals(1, autoLock.startedActivities());
    }

    @Test
    public void delayIsClampedToTheSupportedRange() {
        configuredDelay = 3_600_000L;
        autoLock.onActivityStarted();
        autoLock.onActivityStopped(false);
        assertEquals(VaultAutoLock.MAX_DELAY_MS, scheduler.delay);
        assertEquals(0L, VaultAutoLock.clampDelay(-5L));
    }

    private static final class FakeScheduler implements VaultAutoLock.Scheduler {
        Runnable task;
        long delay = -1;

        @Override
        public void schedule(@NonNull Runnable task, long delayMs) {
            this.task = task;
            this.delay = delayMs;
        }

        @Override
        public void cancel(@NonNull Runnable task) {
            if (this.task == task) this.task = null;
        }

        boolean pending() {
            return task != null;
        }

        void fire() {
            Runnable running = task;
            task = null;
            running.run();
        }
    }
}
