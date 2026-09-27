package dev.clipvault.app.quickpaste

import android.view.KeyEvent

enum class QuickPasteKeyAction { UP, DOWN, PAGE_UP, PAGE_DOWN, FIRST, LAST, CONFIRM, CLOSE }

/** Hardware-keyboard map for the DeX flow: type to search, arrows to pick, Enter to copy, Esc to close. */
object QuickPasteKeys {
    const val PAGE_SIZE = 5

    /** Home/End stay with the search field so they keep moving the text cursor; Ctrl+Home/End jump the list. */
    fun action(keyCode: Int, ctrl: Boolean = false): QuickPasteKeyAction? = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> QuickPasteKeyAction.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> QuickPasteKeyAction.DOWN
        KeyEvent.KEYCODE_PAGE_UP -> QuickPasteKeyAction.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> QuickPasteKeyAction.PAGE_DOWN
        KeyEvent.KEYCODE_MOVE_HOME -> if (ctrl) QuickPasteKeyAction.FIRST else null
        KeyEvent.KEYCODE_MOVE_END -> if (ctrl) QuickPasteKeyAction.LAST else null
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> QuickPasteKeyAction.CONFIRM
        KeyEvent.KEYCODE_ESCAPE -> QuickPasteKeyAction.CLOSE
        else -> null
    }
}

object QuickPasteSelection {
    /** New selected index after [action]; clamps instead of wrapping so holding a key stops at the ends. */
    fun move(current: Int, action: QuickPasteKeyAction, count: Int): Int {
        if (count <= 0) return 0
        val target = when (action) {
            QuickPasteKeyAction.UP -> current - 1
            QuickPasteKeyAction.DOWN -> current + 1
            QuickPasteKeyAction.PAGE_UP -> current - QuickPasteKeys.PAGE_SIZE
            QuickPasteKeyAction.PAGE_DOWN -> current + QuickPasteKeys.PAGE_SIZE
            QuickPasteKeyAction.FIRST -> 0
            QuickPasteKeyAction.LAST -> count - 1
            QuickPasteKeyAction.CONFIRM, QuickPasteKeyAction.CLOSE -> current
        }
        return target.coerceIn(0, count - 1)
    }
}
