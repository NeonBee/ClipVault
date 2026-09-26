package dev.clipvault.app.quickpaste

import android.view.KeyEvent
import dev.clipvault.app.quickpaste.QuickPasteKeyAction.CLOSE
import dev.clipvault.app.quickpaste.QuickPasteKeyAction.CONFIRM
import dev.clipvault.app.quickpaste.QuickPasteKeyAction.DOWN
import dev.clipvault.app.quickpaste.QuickPasteKeyAction.FIRST
import dev.clipvault.app.quickpaste.QuickPasteKeyAction.LAST
import dev.clipvault.app.quickpaste.QuickPasteKeyAction.PAGE_DOWN
import dev.clipvault.app.quickpaste.QuickPasteKeyAction.PAGE_UP
import dev.clipvault.app.quickpaste.QuickPasteKeyAction.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickPasteKeysTest {
    @Test fun keyboardMapCoversTheDexFlow() {
        assertEquals(UP, QuickPasteKeys.action(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(DOWN, QuickPasteKeys.action(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(PAGE_UP, QuickPasteKeys.action(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(PAGE_DOWN, QuickPasteKeys.action(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(CONFIRM, QuickPasteKeys.action(KeyEvent.KEYCODE_ENTER))
        assertEquals(CONFIRM, QuickPasteKeys.action(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(CLOSE, QuickPasteKeys.action(KeyEvent.KEYCODE_ESCAPE))
    }

    @Test fun textEditingKeysStayWithTheSearchField() {
        assertNull(QuickPasteKeys.action(KeyEvent.KEYCODE_A))
        assertNull(QuickPasteKeys.action(KeyEvent.KEYCODE_DEL))
        assertNull(QuickPasteKeys.action(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(QuickPasteKeys.action(KeyEvent.KEYCODE_MOVE_HOME))
        assertNull(QuickPasteKeys.action(KeyEvent.KEYCODE_MOVE_END))
        assertEquals(FIRST, QuickPasteKeys.action(KeyEvent.KEYCODE_MOVE_HOME, ctrl = true))
        assertEquals(LAST, QuickPasteKeys.action(KeyEvent.KEYCODE_MOVE_END, ctrl = true))
    }

    @Test fun selectionClampsAtBothEnds() {
        assertEquals(0, QuickPasteSelection.move(0, UP, 3))
        assertEquals(1, QuickPasteSelection.move(0, DOWN, 3))
        assertEquals(2, QuickPasteSelection.move(2, DOWN, 3))
        assertEquals(2, QuickPasteSelection.move(0, PAGE_DOWN, 3))
        assertEquals(0, QuickPasteSelection.move(2, PAGE_UP, 3))
        assertEquals(6, QuickPasteSelection.move(1, PAGE_DOWN, 20))
        assertEquals(19, QuickPasteSelection.move(1, LAST, 20))
        assertEquals(0, QuickPasteSelection.move(7, FIRST, 20))
        assertEquals(4, QuickPasteSelection.move(4, CONFIRM, 20))
    }

    @Test fun emptyResultsKeepSelectionAtZero() {
        assertEquals(0, QuickPasteSelection.move(0, DOWN, 0))
        assertEquals(0, QuickPasteSelection.move(5, UP, 0))
    }
}
