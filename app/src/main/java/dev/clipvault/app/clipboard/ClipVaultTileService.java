package dev.clipvault.app.clipboard;

import android.app.PendingIntent;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import androidx.core.content.ContextCompat;

import dev.clipvault.app.ClipVaultApp;
import dev.clipvault.app.MainActivity;
import dev.clipvault.app.R;

public final class ClipVaultTileService extends TileService {
    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        ClipVaultApp app = (ClipVaultApp) getApplication();
        boolean enabled = app.settings().getBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false);
        if (enabled) {
            startService(new Intent(this, ClipboardCaptureService.class)
                    .setAction(ClipboardCaptureService.ACTION_STOP));
            app.settings().edit().putBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false).apply();
        } else if (ShizukuController.hasPermission()) {
            ContextCompat.startForegroundService(this, new Intent(this, ClipboardCaptureService.class)
                    .setAction(ClipboardCaptureService.ACTION_START));
            app.settings().edit().putBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, true).apply();
        } else {
            openApp();
        }
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean enabled = getSharedPreferences(ClipVaultApp.PREFS, MODE_PRIVATE)
                .getBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false);
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        if (Build.VERSION.SDK_INT >= 29) {
            tile.setSubtitle(getString(enabled ? R.string.capture_running : R.string.capture_stopped));
        }
        tile.updateTile();
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private void openApp() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (Build.VERSION.SDK_INT >= 34) {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 9, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            startActivityAndCollapse(pendingIntent);
        } else {
            //noinspection deprecation
            startActivityAndCollapse(intent);
        }
    }
}
