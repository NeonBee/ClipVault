package dev.clipvault.app.quickpaste

/**
 * WP-06P-A: an ordinary QuickPaste window, not an always-on-top overlay.
 * Existing copy-and-close stays the default until the user opts in.
 */
internal object QuickPasteCopyPolicy {
    fun shouldCloseAfterCopy(keepOpenAfterCopy: Boolean): Boolean = !keepOpenAfterCopy
}
