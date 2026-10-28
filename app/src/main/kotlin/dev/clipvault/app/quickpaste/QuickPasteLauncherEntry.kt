package dev.clipvault.app.quickpaste

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * WP-06S opt-in Samsung DeX app-shortcut launcher entry.
 *
 * The manifest alias is disabled by default, exposes only MAIN/LAUNCHER, and routes to the
 * existing biometric-protected QuickPasteActivity (which remains exported=false).
 *
 * When enabled, OTHER apps may launch QuickPaste through this exported alias. This must never
 * grant vault access, skip authentication, inspect arbitrary key events or auto-copy a clip.
 * The user can disable the extra launcher entry at any time in ClipVault Settings.
 */
object QuickPasteLauncherEntry {
    // The component CLASS belongs to the source namespace, not the debug applicationId.
    // Debug APKs install as dev.clipvault.app.debug, but Activity classes keep
    // dev.clipvault.app.quickpaste.*; deriving the alias from packageName would be wrong.
    private const val ALIAS_SIMPLE_NAME = "QuickPasteLauncherAlias"

    fun component(context: Context): ComponentName =
        ComponentName(
            context.packageName,
            QuickPasteActivity::class.java.name.substringBeforeLast('.') + "." + ALIAS_SIMPLE_NAME,
        )

    /** Default (manifest) state is disabled; only explicit opt-in enables the alias. */
    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }
}
