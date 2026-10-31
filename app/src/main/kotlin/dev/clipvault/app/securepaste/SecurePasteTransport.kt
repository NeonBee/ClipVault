package dev.clipvault.app.securepaste

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

/**
 * The only WP-07B text output path. Fail closed: no ClipboardManager, capture or staging fallback.
 * A positive commitText return means that the editor accepted the request, not that the app
 * persisted or protected the inserted plaintext.
 */
internal object SecurePasteTransport {
    fun permitted(
        vaultOpen: Boolean,
        expectedEpoch: Long,
        currentEpoch: Long,
        deviceLocked: Boolean,
        editorPackage: String?,
        expectedPackage: String?,
        inputType: Int,
        alreadyInserting: Boolean,
        ownPackage: String? = null,
    ): Boolean {
        if (!vaultOpen || expectedEpoch != currentEpoch || deviceLocked || alreadyInserting) return false
        if (editorPackage.isNullOrBlank() || editorPackage != expectedPackage ||
            editorPackage == ownPackage) return false
        // PoC is intentionally restricted to ordinary text editors. TYPE_NULL requires raw key
        // events; numeric/phone/date and password fields need their own tested policies.
        if (inputType == EditorInfo.TYPE_NULL) return false
        if ((inputType and InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        ) return false
        return true
    }

    fun commit(connection: InputConnection?, plaintext: String): Boolean {
        if (connection == null || plaintext.isEmpty()) return false
        return try {
            connection.commitText(plaintext, 1)
        } catch (_: RuntimeException) {
            false
        }
    }
}
