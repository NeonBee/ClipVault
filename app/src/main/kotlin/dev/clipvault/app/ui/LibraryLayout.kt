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
     * The freeform (DeX) window floor declared as `<layout>` in AndroidManifest.xml. The manifest
     * cannot read these constants, so [LibraryLayoutTest] is what keeps the two in step: if a
     * library row grows, the budget below no longer fits and the floor has to be revisited.
     *
     * The window is floored rather than the layout collapsing, because below this height the
     * library shows filter chips but no clip at all. [MIN_USABLE_WIDTH_DP] does the same for the
     * top bar actions.
     */
    const val MIN_USABLE_HEIGHT_DP = 380

    /** Four 48dp top bar actions plus a readable title slot; narrower and the actions overflow. */
    const val MIN_USABLE_WIDTH_DP = 320

    /** Material 3 small top app bar. */
    const val TOP_BAR_DP = 64
    /** Filter chip row: 32dp chip plus 8dp content padding above and below. */
    const val FILTER_CHIPS_DP = 48
    /** Clip card with a one-line body: 16dp padding, 20dp title, 24dp body, 48dp action row, 9dp gaps. */
    const val CLIP_CARD_DP = 142
    /** LazyColumn content padding, 12dp above and below. */
    const val LIST_PADDING_DP = 24
    /** Material 3 navigation bar. */
    const val BOTTOM_MENU_DP = 80

    /**
     * Height needed for filter chips, one whole clip and the bottom menu at once. A two-line clip
     * body needs 24dp more, which is the headroom [MIN_USABLE_HEIGHT_DP] adds on top of this.
     */
    const val MIN_USABLE_HEIGHT_BUDGET_DP =
        TOP_BAR_DP + FILTER_CHIPS_DP + CLIP_CARD_DP + LIST_PADDING_DP + BOTTOM_MENU_DP

    /**
     * An active query keeps the field visible even when short: otherwise a filtered list would show
     * no sign of the filter and no way to clear it.
     */
    fun showInlineSearch(availableHeightDp: Float, query: String): Boolean =
        availableHeightDp >= COMPACT_HEIGHT_DP || query.isNotEmpty()
}
