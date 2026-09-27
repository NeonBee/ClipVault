package dev.clipvault.app.quickpaste

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.clipvault.app.R

/**
 * Launcher / DeX taskbar long-press entry. A dynamic shortcut is used instead of res/xml/shortcuts.xml
 * because a static shortcut needs a literal targetPackage, which breaks the ".debug" applicationId.
 * The launcher starts it as ClipVault, so QuickPasteActivity can stay unexported.
 */
object QuickPasteShortcut {
    const val ID = "quick_paste"

    fun publish(context: Context) {
        val intent = Intent(context, QuickPasteActivity::class.java).setAction(Intent.ACTION_VIEW)
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(context.getString(R.string.quick_paste_title))
            .setLongLabel(context.getString(R.string.quick_paste_shortcut_long))
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_vault))
            .setIntent(intent)
            .build()
        // Rate limiting or a launcher without shortcut support must not break the main window.
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }
}
