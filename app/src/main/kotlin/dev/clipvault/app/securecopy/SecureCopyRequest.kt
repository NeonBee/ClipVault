package dev.clipvault.app.securecopy

import android.content.Intent

/**
 * Explicit user-selected text (PROCESS_TEXT) or explicit text share (ACTION_SEND) only.
 * Do not read the global clipboard, consume ACTION_SEND_MULTIPLE/STREAM/ClipData,
 * infer missing text from HTML or use the background capture/staging path.
 */
internal object SecureCopyRequest {
    const val MAX_SELECTED_CHARS = 128_000

    fun incomingText(intent: Intent?): String? {
        if (intent == null || intent.type != "text/plain") return null
        val extra = when (intent.action) {
            Intent.ACTION_PROCESS_TEXT -> Intent.EXTRA_PROCESS_TEXT
            Intent.ACTION_SEND -> Intent.EXTRA_TEXT
            else -> return null
        }
        // Intent data is untrusted IPC. Keep only bounded text, not content URIs/streams.
        // Do not trim or persist source text here; the existing repository classifier
        // performs its documented normalization when inserting into SQLCipher.
        return runCatching {
            val raw = intent.getCharSequenceExtra(extra) ?: return@runCatching null
            if (raw.length !in 1..MAX_SELECTED_CHARS) return@runCatching null
            raw.toString().takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}

/** Pure decision logic; also checked immediately before the SQLCipher write under lock epoch. */
internal object SecureCopyGate {
    fun canCommit(isUnlocked: Boolean, requestEpoch: Long, currentEpoch: Long): Boolean =
        isUnlocked && requestEpoch == currentEpoch
}
