package dev.clipvault.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLayoutTest {
    @Test fun inlineSearchHidesOnlyBelowTheCompactHeight() {
        assertTrue(LibraryLayout.showInlineSearch(LibraryLayout.COMPACT_HEIGHT_DP.toFloat(), ""))
        assertTrue(LibraryLayout.showInlineSearch(800f, ""))
        assertFalse(LibraryLayout.showInlineSearch(LibraryLayout.COMPACT_HEIGHT_DP - 0.5f, ""))
        assertFalse(LibraryLayout.showInlineSearch(260f, ""))
    }

    @Test fun activeQueryKeepsTheFieldSoTheFilterStaysVisibleAndClearable() {
        assertTrue(LibraryLayout.showInlineSearch(260f, "invoice"))
    }

    /**
     * AndroidManifest.xml declares the same floor in `<layout>` for MainActivity. The manifest cannot
     * read the constants, so this budget is what keeps the declared floor honest.
     */
    @Test fun theWindowFloorCoversFilterChipsOneClipAndTheBottomMenu() {
        assertEquals(358, LibraryLayout.MIN_USABLE_HEIGHT_BUDGET_DP)
        assertTrue(
            "the manifest minHeight must leave room for a two-line clip body on top of the budget",
            LibraryLayout.MIN_USABLE_HEIGHT_DP >= LibraryLayout.MIN_USABLE_HEIGHT_BUDGET_DP + 24,
        )
    }

    /** Four 48dp top bar actions plus a readable title slot; the width floor keeps them from overflowing. */
    @Test fun theWidthFloorFitsTheTopBarActionsAndTheTitle() {
        assertTrue(LibraryLayout.MIN_USABLE_WIDTH_DP >= 4 * 48 + 112 + 16)
    }
}
