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
    public static final String ACTION_LOCK = "dev.clipvault.app.capture.LOCK";
    private static final String CHANNEL_ID = "secure_clipboard_capture";
    private static final int NOTIFICATION_ID = 1207;

    private ScheduledExecutorService poller;
    private ClipboardManager clipboardManager;
    private ShizukuController shizukuController;
    private ClipboardCaptureCoordinator coordinator;

    private final ClipboardManager.OnPrimaryClipChangedListener localListener = this::captureLocalClipboard;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        clipboardManager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboardManager.addPrimaryClipChangedListener(localListener);
        coordinator = new ClipboardCaptureCoordinator(
                text -> ((ClipVaultApp) getApplication()).capture(text, System.currentTimeMillis()),
                error -> recordCaptureError(BridgeHealth.of(BridgeHealth.State.READY_POLL)
                        .withError(error).diagnosticCode()));
        shizukuController = new ShizukuController(this, health -> {
            recordCaptureError(health.diagnosticCode());
            recordBridgeState(health.stateCode());
            updateNotification();
        }, coordinator::accept);
        shizukuController.start();
        poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "shizuku-clipboard-poller");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        poller.schedule(this::pollPrivilegedClipboard, 0, TimeUnit.MILLISECONDS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(ClipVaultApp.PREFS, MODE_PRIVATE).edit()
                    .putBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false).apply();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_LOCK.equals(intent.getAction())) {
            ((ClipVaultApp) getApplication()).lockVault();
            updateNotification();
            return START_STICKY;
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

    /** Stores a sanitized state identifier only; clipboard text never reaches preferences or logs. */
    private void recordCaptureError(String code) {
        getSharedPreferences(ClipVaultApp.PREFS, MODE_PRIVATE).edit()
                .putString(ClipVaultApp.PREF_LAST_CAPTURE_ERROR, code).apply();
    }

    private void recordBridgeState(String code) {
        getSharedPreferences(ClipVaultApp.PREFS, MODE_PRIVATE).edit()
                .putString(ClipVaultApp.PREF_BRIDGE_STATE, code).apply();
    }

    private void pollPrivilegedClipboard() {
        long delay = coordinator.poll(shizukuController);
        if (poller != null && !poller.isShutdown()) {
            poller.schedule(this::pollPrivilegedClipboard, delay, TimeUnit.MILLISECONDS);
        }
    }

    private void captureLocalClipboard() {
        try {
            ClipData clip = clipboardManager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence text = clip.getItemAt(0).coerceToText(this);
            coordinator.accept(text == null ? null : text.toString());
        } catch (SecurityException ignored) {
            // Android 10+ blocks this fallback when the app is not focused.
        }
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
        Intent lockIntent = new Intent(this, ClipboardCaptureService.class).setAction(ACTION_LOCK);
        PendingIntent lockPendingIntent = PendingIntent.getService(
                this, 3, lockIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String status = captureStatus();
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_vault)
                .setContentTitle(getString(R.string.capture_notification_title))
                .setContentText(status)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(R.drawable.ic_lock, getString(R.string.lock_now), lockPendingIntent)
                .addAction(R.drawable.ic_bolt, getString(R.string.disable_capture), stopPendingIntent)
                .build();
    }

    private String captureStatus() {
        if (shizukuController == null) return getString(R.string.shizuku_offline);
        BridgeHealth health = shizukuController.health();
        if (health.isReady()) return getString(R.string.capture_notification_body);
        switch (health.state) {
            case NO_SHIZUKU:
            case PERMISSION_REQUIRED:
                return getString(R.string.shizuku_offline);
            default:
                return getString(R.string.bridge_unavailable) + " · " + health.diagnosticCode();
        }
    }

    private void updateNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildNotification());
    }

    @Override
    public void onDestroy() {
        // First: an in-flight poll or bridge callback must not capture once capture is stopped.
        if (coordinator != null) coordinator.close();
        if (clipboardManager != null) clipboardManager.removePrimaryClipChangedListener(localListener);
        if (poller != null) poller.shutdownNow();
        if (shizukuController != null) shizukuController.close();
        // A stopped service must not leave a stale READY_* mode in diagnostics.
        recordBridgeState("");
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
