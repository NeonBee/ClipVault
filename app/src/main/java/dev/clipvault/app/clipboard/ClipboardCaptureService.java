package dev.clipvault.app.clipboard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import dev.clipvault.app.ClipVaultApp;
import dev.clipvault.app.MainActivity;
import dev.clipvault.app.R;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class ClipboardCaptureService extends Service {
    public static final String ACTION_START = "dev.clipvault.app.capture.START";
    public static final String ACTION_STOP = "dev.clipvault.app.capture.STOP";
    private static final String CHANNEL_ID = "secure_clipboard_capture";
    private static final int NOTIFICATION_ID = 1207;

    private final Object lastClipLock = new Object();
    private ScheduledExecutorService poller;
    private ClipboardManager clipboardManager;
    private ShizukuController shizukuController;
    private String lastClip;

    private final ClipboardManager.OnPrimaryClipChangedListener localListener = this::captureLocalClipboard;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboardManager.addPrimaryClipChangedListener(localListener);
        shizukuController = new ShizukuController(this, ready -> updateNotification());
        shizukuController.start();
        poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shizuku-clipboard-poller");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        poller.scheduleWithFixedDelay(this::pollPrivilegedClipboard, 0, 850, TimeUnit.MILLISECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(ClipVaultApp.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        getSharedPreferences(ClipVaultApp.PREFS, MODE_PRIVATE).edit()
                .putBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, true).apply();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        return START_STICKY;
    }

    private void pollPrivilegedClipboard() {
        String text = shizukuController.readText();
        captureIfChanged(text);
    }

    private void captureLocalClipboard() {
        try {
            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            captureIfChanged(text == null ? null : text.toString());
        } catch (SecurityException ignored) {
            // Android 10+ blocks this fallback when the app is not focused.
        }
    }

    private void captureIfChanged(@Nullable String text) {
        if (text == null || text.trim().isEmpty()) return;
        synchronized (lastClipLock) {
            if (text.equals(lastClip)) return;
            lastClip = text;
        }
        ((ClipVaultApp) getApplication()).capture(text, System.currentTimeMillis());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.capture_notification_body));
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, ClipboardCaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String status = shizukuController != null && shizukuController.isReady()
                ? getString(R.string.capture_notification_body)
                : getString(R.string.shizuku_offline);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vault)
                .setContentTitle(getString(R.string.capture_notification_title))
                .setContentText(status)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(R.drawable.ic_lock, getString(R.string.disable_capture), stopPendingIntent)
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        if (clipboardManager != null) clipboardManager.removePrimaryClipChangedListener(localListener);
        if (poller != null) poller.shutdownNow();
        if (shizukuController != null) shizukuController.close();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
