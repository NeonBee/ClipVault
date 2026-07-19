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
import dev.clipvault.app.data.VaultRepository;
import dev.clipvault.app.nativecore.NativeClassifier;
import dev.clipvault.app.security.SecurePendingStore;
import dev.clipvault.app.workers.RetentionWorker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ClipVaultApp extends Application {
    public static final String ACTION_DATA_CHANGED = "dev.clipvault.app.DATA_CHANGED";
    public static final String PREFS = "clipvault_settings";
    public static final String PREF_RETENTION_MONTHS = "retention_months";
    public static final String PREF_SKIP_SENSITIVE = "skip_sensitive";
    public static final String PREF_CAPTURE_ENABLED = "capture_enabled";
    public static final String PREF_ONBOARDED = "onboarded";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "clipvault-io");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private volatile VaultRepository repository;
    private volatile boolean unlocked;
    private SecurePendingStore pendingStore;
    private SharedPreferences settings;

    @Override
    public void onCreate() {
        super.onCreate();
        pendingStore = new SecurePendingStore(this);
        settings = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

            int retention = settings.getInt(PREF_RETENTION_MONTHS, RetentionPolicy.FOREVER);
            long now = System.currentTimeMillis();
            if (retention != RetentionPolicy.FOREVER) {
                long cutoff = RetentionPolicy.cutoffForMonths(retention, now);
                opened.deleteOlderThan(cutoff);
                pendingStore.purgeOlderThan(cutoff);
            }
            int imported = importPending(opened, retention, now);
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
                    target.insert(pendingClip.content, pendingClip.createdAt);
                    imported++;
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

    public void capture(@NonNull String rawText, long createdAt) {
        String normalized = NativeClassifier.normalize(rawText);
        if (normalized.isEmpty()) return;
        if (settings.getBoolean(PREF_SKIP_SENSITIVE, true) && NativeClassifier.isSensitive(normalized)) return;
        ioExecutor.execute(() -> {
            VaultRepository openRepository = repository();
            if (openRepository != null) {
                openRepository.insert(normalized, createdAt);
            } else {
                pendingStore.add(normalized, createdAt);
            }
            notifyDataChanged();
        });
    }

    public void applyRetentionNow() {
        ioExecutor.execute(() -> {
            int retention = settings.getInt(PREF_RETENTION_MONTHS, RetentionPolicy.FOREVER);
            if (retention == RetentionPolicy.FOREVER) return;
            long cutoff = RetentionPolicy.cutoffForMonths(retention, System.currentTimeMillis());
            pendingStore.purgeOlderThan(cutoff);
            VaultRepository openRepository = repository();
            if (openRepository != null) openRepository.deleteOlderThan(cutoff);
            notifyDataChanged();
        });
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
