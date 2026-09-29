package dev.clipvault.app.ui

/**
 * Compact-height policy for the library (WP-06 DeX UX). Short freeform windows lose most of their
 * height to the top bar, the inline search field and the filter chips, so below this available
 * height the inline field is hidden; search stays reachable through the top bar's Advanced Search,
 * which edits the same query.
 */
internal object LibraryLayout {
    /** Available library height (below the app bar insets, above bottom navigation) in dp. */
    const val COMPACT_HEIGHT_DP = 420

    /**
     * An active query keeps the field visible even when short: otherwise a filtered list would show
     * no sign of the filter and no way to clear it.
     */
    fun showInlineSearch(availableHeightDp: Float, query: String): Boolean =
        availableHeightDp >= COMPACT_HEIGHT_DP || query.isNotEmpty()
}
