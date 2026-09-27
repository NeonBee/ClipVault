package dev.clipvault.app.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/** Copy-back path for vault content: always marked sensitive so the system hides the preview. */
object SensitiveClipboard {
    /** ClipDescription.EXTRA_IS_SENSITIVE; the literal is honoured by some pre-33 builds too. */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun clip(text: String): ClipData {
        val clip = ClipData.newPlainText("ClipVault", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else EXTRA_IS_SENSITIVE, true)
        }
        return clip
    }

    fun write(context: Context, text: String) {
        val manager = context.getSystemService(ClipboardManager::class.java)
        manager.setPrimaryClip(clip(text))
    }

    /** Android 13+ shows its own copy confirmation; older releases need an app toast. */
    val systemShowsConfirmation: Boolean get() = Build.VERSION.SDK_INT >= 33
}
