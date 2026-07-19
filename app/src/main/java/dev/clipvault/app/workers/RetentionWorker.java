package dev.clipvault.app.workers;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import dev.clipvault.app.ClipVaultApp;
import dev.clipvault.app.data.RetentionPolicy;

public final class RetentionWorker extends Worker {
    public RetentionWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        ClipVaultApp app = (ClipVaultApp) getApplicationContext();
        SharedPreferences settings = app.settings();
        int months = settings.getInt(ClipVaultApp.PREF_RETENTION_MONTHS, RetentionPolicy.FOREVER);
        if (months == RetentionPolicy.FOREVER) return Result.success();
        try {
            long cutoff = RetentionPolicy.cutoffForMonths(months, System.currentTimeMillis());
            app.pending().purgeOlderThan(cutoff);
            app.applyRetentionNow();
            return Result.success();
        } catch (RuntimeException error) {
            return Result.retry();
        }
    }
}
