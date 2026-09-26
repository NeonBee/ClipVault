package dev.clipvault.app.security;

import androidx.annotation.NonNull;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Process-wide auto-lock policy. The vault locks after the configured delay once no ClipVault
 * activity is started, so MainActivity and QuickPasteActivity share one timer: opening QuickPaste
 * over MainActivity must not lock the vault underneath it, and a vault unlocked from QuickPaste
 * alone must still lock after it closes.
 */
public final class VaultAutoLock {
    public static final long DEFAULT_DELAY_MS = 30_000L;
    public static final long MAX_DELAY_MS = 300_000L;

    public interface Scheduler {
        void schedule(@NonNull Runnable task, long delayMs);

        void cancel(@NonNull Runnable task);
    }

    private final Scheduler scheduler;
    private final LongSupplier configuredDelayMs;
    private final BooleanSupplier unlocked;
    private final Runnable lockTask;
    private int startedActivities;

    public VaultAutoLock(@NonNull Scheduler scheduler, @NonNull LongSupplier configuredDelayMs,
                         @NonNull BooleanSupplier unlocked, @NonNull Runnable lock) {
        this.scheduler = scheduler;
        this.configuredDelayMs = configuredDelayMs;
        this.unlocked = unlocked;
        this.lockTask = lock;
    }

    /** Main thread only. */
    public void onActivityStarted() {
        startedActivities++;
        scheduler.cancel(lockTask);
    }

    /** Main thread only. A configuration change restarts the activity immediately, so it does not arm the timer. */
    public void onActivityStopped(boolean changingConfigurations) {
        if (startedActivities > 0) startedActivities--;
        if (startedActivities == 0 && !changingConfigurations && unlocked.getAsBoolean()) {
            scheduler.cancel(lockTask);
            scheduler.schedule(lockTask, clampDelay(configuredDelayMs.getAsLong()));
        }
    }

    /**
     * Main thread only. Called after an unlock completes. The database opens asynchronously after the
     * biometric prompt, so every window may already have stopped (while the vault was still locked and
     * the stop armed nothing); arm the timer now in that case.
     */
    public void onVaultUnlocked() {
        if (startedActivities == 0 && unlocked.getAsBoolean()) {
            scheduler.cancel(lockTask);
            scheduler.schedule(lockTask, clampDelay(configuredDelayMs.getAsLong()));
        }
    }

    /** Main thread only. Called when the vault is locked by any path so a stale timer does not fire later. */
    public void onVaultLocked() {
        scheduler.cancel(lockTask);
    }

    public int startedActivities() {
        return startedActivities;
    }

    public static long clampDelay(long delayMs) {
        return Math.max(0L, Math.min(delayMs, MAX_DELAY_MS));
    }
}
