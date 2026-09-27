package dev.clipvault.app;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import dev.clipvault.app.data.RetentionPolicy;
import dev.clipvault.app.data.CaptureRuleEngine;
import dev.clipvault.app.data.VaultRepository;
import dev.clipvault.app.nativecore.NativeClassifier;
import dev.clipvault.app.nativecore.TextAnalysis;
import dev.clipvault.app.security.SecurePendingStore;
import dev.clipvault.app.security.DesktopModes;
import dev.clipvault.app.security.VaultAutoLock;
import dev.clipvault.app.security.VaultLockLog;
import dev.clipvault.app.workers.RetentionWorker;

import rikka.shizuku.ShizukuProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.security.GeneralSecurityException;

public final class ClipVaultApp extends Application {
    public static final String ACTION_DATA_CHANGED = "dev.clipvault.app.DATA_CHANGED";
    public static final String PREFS = "clipvault_settings";
    public static final String PREF_RETENTION_MONTHS = "retention_months";
    public static final String PREF_SKIP_SENSITIVE = "skip_sensitive";
    public static final String PREF_CAPTURE_ENABLED = "capture_enabled";
    public static final String PREF_ONBOARDED = "onboarded";
    public static final String PREF_CUSTOM_RETENTION_DAYS = "custom_retention_days";
    public static final String PREF_MAINTENANCE_PENDING = "maintenance_pending";
    public static final String PREF_LAST_CAPTURE_AT = "last_capture_at";
    public static final String PREF_LAST_CAPTURE_ERROR = "last_capture_error";
    /** BridgeHealth.stateCode() of the running capture service; empty when it is stopped. */
    public static final String PREF_BRIDGE_STATE = "bridge_state";
    public static final String PREF_AUTO_LOCK_MS = "auto_lock_ms";
    /** VaultLockLog: sanitized recent lock reasons, newest first. */
    public static final String PREF_LOCK_LOG = "lock_log";
    /** Wall-clock unlock time while the vault is open; left behind only if the process dies unlocked. */
    public static final String PREF_VAULT_OPEN_MARKER = "vault_open_marker";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "clipvault-io");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private volatile VaultRepository repository;
    private volatile boolean unlocked;
    private SecurePendingStore pendingStore;
    private SharedPreferences settings;
    private final CaptureRuleEngine ruleEngine = new CaptureRuleEngine();
    private AppContainer container;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Runnable> lockListeners = new CopyOnWriteArrayList<>();
    private VaultAutoLock autoLock;
    /** Incremented by every lockVault(); an unlock that started in an older epoch must not open the vault. */
    private final AtomicLong lockEpoch = new AtomicLong();
    /** elapsedRealtime of the last successful unlock in this process, or -1. */
    private volatile long unlockedAtElapsed = -1L;
    private final Object lockLogGuard = new Object();

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Content providers start before onCreate(). Stop ShizukuProvider from auto-attaching Sui
        // (a root backend); the clipboard bridge only accepts the ADB shell identity.
        ShizukuProvider.disableAutomaticSuiInitialization();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        pendingStore = new SecurePendingStore(this);
        settings = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        container = new AppContainer(this);
        recordProcessRestartWhileOpen();
        installAutoLock();
        scheduleRetentionMaintenance();
    }

    private void installAutoLock() {
        autoLock = new VaultAutoLock(new VaultAutoLock.Scheduler() {
            @Override
            public void schedule(@NonNull Runnable task, long delayMs) {
                mainHandler.postDelayed(task, delayMs);
            }

            @Override
            public void cancel(@NonNull Runnable task) {
                mainHandler.removeCallbacks(task);
            }
        }, () -> settings.getLong(PREF_AUTO_LOCK_MS, VaultAutoLock.DEFAULT_DELAY_MS), this::isUnlocked,
                () -> lockVault(VaultLockLog.Reason.AUTO_LOCK_TIMEOUT));
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(@NonNull Activity activity) { autoLock.onActivityStarted(); }
            @Override public void onActivityStopped(@NonNull Activity activity) {
                autoLock.onActivityStopped(activity.isChangingConfigurations());
            }
            @Override public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) { }
            @Override public void onActivityResumed(@NonNull Activity activity) { }
            @Override public void onActivityPaused(@NonNull Activity activity) { }
            @Override public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) { }
            @Override public void onActivityDestroyed(@NonNull Activity activity) { }
        });
        // Screen off locks immediately whichever ClipVault window (or none) unlocked the vault.
        // SCREEN_OFF is a protected system broadcast; EXPORTED keeps pre-33 delivery identical to
        // the old MainActivity receiver (NOT_EXPORTED adds a permission requirement below API 33).
        ContextCompat.registerReceiver(this, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) lockVault(VaultLockLog.Reason.SCREEN_OFF);
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_EXPORTED);
    }

    /** Listeners run on the main thread after the vault has been marked locked. */
    public void addLockListener(@NonNull Runnable listener) {
        lockListeners.add(listener);
    }

    public void removeLockListener(@NonNull Runnable listener) {
        lockListeners.remove(listener);
    }

    @NonNull
    public ExecutorService io() {
        return ioExecutor;
    }

    @NonNull
    public SecurePendingStore pending() {
        return pendingStore;
    }

    @NonNull
    public SharedPreferences settings() {
        return settings;
    }

    @NonNull
    public AppContainer container() {
        return container;
    }

    public boolean isUnlocked() {
        return unlocked && repository != null;
    }

    @Nullable
    public VaultRepository repository() {
        return unlocked ? repository : null;
    }

    /** Capture before starting an unlock and pass to {@link #openVault(byte[], long)}. */
    public long lockEpoch() {
        return lockEpoch.get();
    }

    public int openVault(@NonNull byte[] databaseKey) {
        return openVault(databaseKey, lockEpoch.get());
    }

    /**
     * Opens the vault unless it was locked (screen off, keyguard, explicit lock) after the unlock that
     * produced this key began. Call on the IO executor.
     *
     * @throws UnlockInterruptedException when the lock epoch changed; the key is wiped and nothing stays open
     */
    public int openVault(@NonNull byte[] databaseKey, long expectedLockEpoch) {
        if (Thread.currentThread() == android.os.Looper.getMainLooper().getThread()) {
            throw new IllegalStateException("Database must be opened off the main thread");
        }
        try {
            if (lockEpoch.get() != expectedLockEpoch) throw new UnlockInterruptedException();
            VaultRepository opened = new VaultRepository(this, databaseKey);
            synchronized (lockEpoch) {
                // lockVault() may have run while SQLCipher was opening; do not publish a stale unlock.
                if (lockEpoch.get() != expectedLockEpoch) {
                    opened.close();
                    throw new UnlockInterruptedException();
                }
                VaultRepository old = repository;
                repository = opened;
                unlocked = true;
                unlockedAtElapsed = SystemClock.elapsedRealtime();
                // Inside the epoch lock: a lockVault() that sees unlocked=true removes this marker after it.
                settings.edit().putLong(PREF_VAULT_OPEN_MARKER, System.currentTimeMillis()).apply();
                if (old != null) old.close();
            }
            mainHandler.post(() -> { if (autoLock != null) autoLock.onVaultUnlocked(); });

            ruleEngine.replace(opened.rules());

            long now = System.currentTimeMillis();
            int retention = settings.getInt(PREF_RETENTION_MONTHS, RetentionPolicy.FOREVER);
            runMaintenance(opened, now);
            int imported = importPending(opened, retention, now);
            settings.edit().putBoolean(PREF_MAINTENANCE_PENDING, false).apply();
            notifyDataChanged();
            return imported;
        } finally {
            Arrays.fill(databaseKey, (byte) 0);
        }
    }

    private int importPending(VaultRepository target, int retention, long now) {
        int imported = 0;
        while (true) {
            List<SecurePendingStore.PendingClip> batch = pendingStore.readBatch(200);
            if (batch.isEmpty()) break;
            List<Long> consumed = new ArrayList<>(batch.size());
            for (SecurePendingStore.PendingClip pendingClip : batch) {
                if (RetentionPolicy.shouldKeep(pendingClip.createdAt, retention, now)) {
                    TextAnalysis analysis = NativeClassifier.analyze(pendingClip.content);
                    long matchingRule = ruleEngine.firstMatch(analysis);
                    if (matchingRule == -1L) {
                        target.insert(analysis, pendingClip.createdAt);
                        imported++;
                    } else {
                        target.recordRuleMatch(matchingRule, pendingClip.createdAt);
                    }
                }
                consumed.add(pendingClip.id);
            }
            pendingStore.deleteIds(consumed);
            if (batch.size() < 200) break;
        }
        return imported;
    }

    /** Idempotent; any thread. Lock listeners always run so every window drops decrypted state. */
    public void lockVault() {
        lockVault(VaultLockLog.Reason.OTHER);
    }

    /** As {@link #lockVault()}, recording [reason] in the diagnostics lock log if the vault was open. */
    public void lockVault(@NonNull VaultLockLog.Reason reason) {
        boolean wasOpen;
        synchronized (lockEpoch) {
            lockEpoch.incrementAndGet();
            wasOpen = unlocked;
            unlocked = false;
        }
        if (wasOpen) recordLock(reason);
        ioExecutor.execute(() -> {
            VaultRepository closing = repository;
            repository = null;
            if (closing != null) closing.close();
        });
        Runnable notify = () -> {
            if (autoLock != null) autoLock.onVaultLocked();
            for (Runnable listener : lockListeners) listener.run();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) notify.run();
        else mainHandler.post(notify);
    }

    /** Irreversibly removes vault and staging data after explicit UI confirmation. Call on the IO executor. */
    public void resetVaultFiles() throws GeneralSecurityException {
        unlocked = false;
        VaultRepository closing = repository;
        repository = null;
        if (closing != null) closing.close();
        pendingStore.close();
        if (!deleteDatabase("clipvault.db")) {
            java.io.File database = getDatabasePath("clipvault.db");
            if (database.exists()) throw new IllegalStateException("Could not delete the encrypted vault");
        }
        if (!deleteDatabase("pending_encrypted.db")) {
            java.io.File pendingDatabase = getDatabasePath("pending_encrypted.db");
            if (pendingDatabase.exists()) throw new IllegalStateException("Could not delete encrypted staging data");
        }
        SecurePendingStore.destroyKeys();
        pendingStore = new SecurePendingStore(this);
        ruleEngine.replace(java.util.Collections.emptyList());
        settings.edit().putBoolean(PREF_CAPTURE_ENABLED, false)
                .putBoolean(PREF_MAINTENANCE_PENDING, false).remove(PREF_VAULT_OPEN_MARKER).apply();
    }

    private void recordLock(@NonNull VaultLockLog.Reason reason) {
        long since = unlockedAtElapsed >= 0 ? SystemClock.elapsedRealtime() - unlockedAtElapsed : VaultLockLog.UNKNOWN;
        int windows = autoLock != null ? autoLock.startedActivities() : VaultLockLog.UNKNOWN;
        appendLockEvent(snapshot(reason, since, windows));
    }

    /**
     * A leftover open marker means the previous process ended (killed, crashed, rebooted) while the
     * vault was unlocked. That drops the key without any lockVault() call, so record it explicitly.
     */
    private void recordProcessRestartWhileOpen() {
        if (!settings.contains(PREF_VAULT_OPEN_MARKER)) return;
        long openedAt = settings.getLong(PREF_VAULT_OPEN_MARKER, 0L);
        long since = openedAt > 0 ? Math.max(0L, System.currentTimeMillis() - openedAt) : VaultLockLog.UNKNOWN;
        appendLockEvent(snapshot(VaultLockLog.Reason.PROCESS_RESTART, since, VaultLockLog.UNKNOWN));
    }

    @NonNull
    private VaultLockLog.Event snapshot(@NonNull VaultLockLog.Reason reason, long sinceUnlockMs, int windows) {
        int interactive = VaultLockLog.UNKNOWN;
        int keyguardLocked = VaultLockLog.UNKNOWN;
        int deviceLocked = VaultLockLog.UNKNOWN;
        try {
            PowerManager power = getSystemService(PowerManager.class);
            if (power != null) interactive = VaultLockLog.flag(power.isInteractive());
            KeyguardManager keyguard = getSystemService(KeyguardManager.class);
            if (keyguard != null) {
                keyguardLocked = VaultLockLog.flag(keyguard.isKeyguardLocked());
                deviceLocked = VaultLockLog.flag(keyguard.isDeviceLocked());
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never break locking.
        }
        return new VaultLockLog.Event(reason, System.currentTimeMillis(), sinceUnlockMs, windows,
                interactive, keyguardLocked, deviceLocked, DesktopModes.samsungDex(getResources().getConfiguration()));
    }

    private void appendLockEvent(@NonNull VaultLockLog.Event event) {
        synchronized (lockLogGuard) {
            String log = VaultLockLog.append(settings.getString(PREF_LOCK_LOG, ""), event);
            settings.edit().putString(PREF_LOCK_LOG, log).remove(PREF_VAULT_OPEN_MARKER).apply();
        }
    }

    /** The vault was locked while this unlock was in progress; the user must authenticate again. */
    public static final class UnlockInterruptedException extends IllegalStateException {
        public UnlockInterruptedException() {
            super("The vault was locked while unlocking");
        }
    }

    public void capture(@NonNull String rawText, long createdAt) {
        TextAnalysis analysis = NativeClassifier.analyze(rawText);
        if (analysis.normalized.isEmpty()) return;
        if (settings.getBoolean(PREF_SKIP_SENSITIVE, true) && analysis.sensitive) return;
        long matchingRule = ruleEngine.firstMatch(analysis);
        if (matchingRule != -1L) {
            ioExecutor.execute(() -> {
                VaultRepository openRepository = repository();
                if (openRepository != null) openRepository.recordRuleMatch(matchingRule, createdAt);
                notifyDataChanged();
            });
            return;
        }
        ioExecutor.execute(() -> {
            VaultRepository openRepository = repository();
            if (openRepository != null) {
                openRepository.insert(analysis, createdAt);
            } else {
                pendingStore.add(analysis.normalized, createdAt);
            }
            settings.edit().putLong(PREF_LAST_CAPTURE_AT, createdAt)
                    .putString(PREF_LAST_CAPTURE_ERROR, "").apply();
            notifyDataChanged();
        });
    }

    public void applyRetentionNow() {
        ioExecutor.execute(() -> {
            VaultRepository openRepository = repository();
            if (openRepository == null) {
                settings.edit().putBoolean(PREF_MAINTENANCE_PENDING, true).apply();
                return;
            }
            runMaintenance(openRepository, System.currentTimeMillis());
            notifyDataChanged();
        });
    }

    public void refreshRules() {
        ioExecutor.execute(() -> {
            VaultRepository openRepository = repository();
            if (openRepository != null) ruleEngine.replace(openRepository.rules());
        });
    }

    private void runMaintenance(@NonNull VaultRepository target, long now) {
        int customDays = settings.getInt(PREF_CUSTOM_RETENTION_DAYS, 0);
        int months = settings.getInt(PREF_RETENTION_MONTHS, RetentionPolicy.FOREVER);
        long cutoff = Long.MIN_VALUE;
        if (customDays >= RetentionPolicy.MIN_CUSTOM_DAYS && customDays <= RetentionPolicy.MAX_CUSTOM_DAYS) {
            cutoff = RetentionPolicy.cutoffForCustomDays(customDays, now);
        } else if (months != RetentionPolicy.FOREVER) {
            cutoff = RetentionPolicy.cutoffForMonths(months, now);
        }
        if (cutoff != Long.MIN_VALUE) {
            target.softDeleteOlderThan(cutoff, now);
            pendingStore.purgeOlderThan(cutoff);
        }
        target.purgeTrashOlderThan(RetentionPolicy.trashCutoff(now));
    }

    private void notifyDataChanged() {
        sendBroadcast(new Intent(ACTION_DATA_CHANGED).setPackage(getPackageName()));
    }

    private void scheduleRetentionMaintenance() {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                RetentionWorker.class, 24, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                "clipvault-retention", ExistingPeriodicWorkPolicy.UPDATE, request);
    }
}
