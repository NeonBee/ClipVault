package dev.clipvault.app.securecopy

import android.content.Intent

/**
 * Explicit selection-toolbar ingress only. Do not read the global clipboard, infer payloads from
 * ACTION_SEND, normalize/truncate user-selected text, or re-use background capture/staging.
 */
internal object SecureCopyRequest {
    const val MAX_SELECTED_CHARS = 128_000

    fun selectedText(intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_PROCESS_TEXT || intent.type != "text/plain") return null
        // Input comes from another process. Reject missing, malformed or oversized CharSequences
        // without saving them in preferences, logs or saved instance state.
        return runCatching {
            val raw = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT) ?: return@runCatching null
            if (raw.length !in 1..MAX_SELECTED_CHARS) return@runCatching null
            raw.toString().takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}

/** Pure decision logic; also checked immediately before the SQLCipher write under the lock epoch. */
internal object SecureCopyGate {
    fun canCommit(isUnlocked: Boolean, requestEpoch: Long, currentEpoch: Long): Boolean =
        isUnlocked && requestEpoch == currentEpoch
}
