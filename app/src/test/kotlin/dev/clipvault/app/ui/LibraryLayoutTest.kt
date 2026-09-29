package dev.clipvault.app.ui

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
}
