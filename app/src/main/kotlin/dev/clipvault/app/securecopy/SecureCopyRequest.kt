package dev.clipvault.app.securecopy

import android.content.Intent

/**
 * Explicit user-selected text (PROCESS_TEXT) or explicit text share (ACTION_SEND) only.
 * Do not read the global clipboard, consume ACTION_SEND_MULTIPLE/STREAM/ClipData,
 * infer missing text from HTML or use the background capture/staging path.
 */
internal object SecureCopyRequest {
    const val MAX_SELECTED_CHARS = 128_000

    /**
     * Parse one explicit request and discard the original Intent's payload references.
     * A retained Activity Intent is not a plaintext cache, including on invalid inputs.
     */
    fun consumeAndClear(intent: Intent?): String? {
        val text = incomingText(intent)
        intent?.removeExtra(Intent.EXTRA_PROCESS_TEXT)
        intent?.removeExtra(Intent.EXTRA_TEXT)
        intent?.removeExtra(Intent.EXTRA_STREAM)
        intent?.clipData = null
        intent?.data = null
        return text
    }

    fun incomingText(intent: Intent?): String? {
        if (intent == null || intent.type != "text/plain") return null
        // Mixed text + attachment/URI is not a pure-text secure ingress. Never
        // silently discard a second payload and imply the entire share was saved.
        if (intent.hasExtra(Intent.EXTRA_STREAM) || intent.data != null) return null
        // Some share senders put an attachment into ClipData while EXTRA_TEXT
        // contains a caption. Pure text ClipData is fine; URI/Intent items are not.
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) {
                val item = clip.getItemAt(index)
                if (item.uri != null || item.intent != null) return null
            }
        }
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
    fun canCommit(isUnlocked: Boolean, requestEpoch: Long, currentEpoch: Long, requestActive: Boolean): Boolean =
        requestActive && isUnlocked && requestEpoch == currentEpoch
}
