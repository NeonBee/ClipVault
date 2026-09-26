package dev.clipvault.app;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import dev.clipvault.app.data.RetentionPolicy;
import dev.clipvault.app.data.CaptureRuleEngine;
import dev.clipvault.app.data.VaultRepository;
import dev.clipvault.app.nativecore.NativeClassifier;
import dev.clipvault.app.nativecore.TextAnalysis;
import dev.clipvault.app.security.SecurePendingStore;
import dev.clipvault.app.workers.RetentionWorker;

import rikka.shizuku.ShizukuProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
        scheduleRetentionMaintenance();
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

    public int openVault(@NonNull byte[] databaseKey) {
        if (Thread.currentThread() == android.os.Looper.getMainLooper().getThread()) {
            throw new IllegalStateException("Database must be opened off the main thread");
        }
        try {
            VaultRepository opened = new VaultRepository(this, databaseKey);
            VaultRepository old = repository;
            repository = opened;
            unlocked = true;
            if (old != null) old.close();

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

    public void lockVault() {
        unlocked = false;
        ioExecutor.execute(() -> {
            VaultRepository closing = repository;
            repository = null;
            if (closing != null) closing.close();
        });
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
                .putBoolean(PREF_MAINTENANCE_PENDING, false).apply();
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
