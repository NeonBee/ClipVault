package dev.clipvault.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLayoutTest {
    @Test fun fullHeightAlwaysShowsInlineSearch() {
        assertTrue(LibraryLayout.showInlineSearch(LibraryLayout.COMPACT_HEIGHT_DP.toFloat(), false))
        assertTrue(LibraryLayout.showInlineSearch(800f, false))
    }

    @Test fun compactHeightRequiresExplicitExpansion() {
        val compact = LibraryLayout.COMPACT_HEIGHT_DP - 0.5f
        assertTrue(LibraryLayout.isCompactHeight(compact))
        assertFalse(LibraryLayout.showInlineSearch(compact, false))
        assertTrue(LibraryLayout.showInlineSearch(compact, true))
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

    /**
     * Compact top bar keeps the 320dp floor by trading the two-line title for a 64dp one-line title:
     * four actions + search affordance + compact title + spacing.
     */
    @Test fun theWidthFloorFitsCompactSearchAndTopBarActions() {
        assertTrue(LibraryLayout.MIN_USABLE_WIDTH_DP >= 4 * 48 + 48 + 64 + 16)
    }
}
