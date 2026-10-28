package dev.clipvault.app.quickpaste

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.clipvault.app.R
import dev.clipvault.app.data.ClipItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** WP-06: the QuickPaste freeform minimum is 240x260dp (manifest <layout>); the keyboard flow must still work there. */
@OptIn(ExperimentalTestApi::class)
class QuickPasteCompactLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var state by mutableStateOf(QuickPasteState())
    private var confirmed: ClipItem? = null
    private var closed = 0
    private var unlocks = 0
    private var keepOpenAfterCopy by mutableStateOf(false)

    private fun showAtMinimumSize(initial: QuickPasteState, width: Dp = 240.dp, height: Dp = 260.dp) {
        state = initial
        compose.setContent {
            Box(Modifier.size(width = width, height = height)) {
                QuickPasteScreen(
                    state = state,
                    onQueryChange = { state = state.copy(query = it) },
                    onKey = { state = state.copy(selected = QuickPasteSelection.move(state.selected, it, state.results.size)) },
                    onSelectIndex = { state = state.copy(selected = it) },
                    onConfirm = { confirmed = state.selectedItem },
                    onClose = { closed++ },
                    onUnlock = { unlocks++ },
                    onOpenApp = {},
                    keepOpenAfterCopy = keepOpenAfterCopy,
                    onKeepOpenChange = { keepOpenAfterCopy = it },
                )
            }
        }
    }

    @Test fun searchListHintAndKeysFitTheMinimumWindow() {
        val clips = (1L..6L).map { id ->
            ClipItem(id, "clip number $id with a fairly long line of text to wrap", "", "", "", 0L,
                System.currentTimeMillis() - id * 60_000L, 1, 40, 0, false, false, null, null)
        }
        showAtMinimumSize(QuickPasteState(gate = QuickPasteGate.READY, results = clips, searched = true))
        val search = compose.onNodeWithTag(QuickPasteTags.SEARCH)
        search.assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag(QuickPasteTags.row(0)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.quick_paste_keys_hint)).assertIsDisplayed()

        // Selection past the visible rows scrolls the list; Enter copies it, Esc closes.
        search.performKeyInput { repeat(4) { pressKey(Key.DirectionDown) } }
        compose.onNodeWithTag(QuickPasteTags.row(4)).assertIsDisplayed()
        search.performKeyInput { pressKey(Key.Enter) }
        assertEquals(5L, confirmed?.id)
        search.performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, closed)
    }

    @Test fun keepOpenSettingTogglesWithinTheMinimumWindow() {
        keepOpenAfterCopy = false
        showAtMinimumSize(QuickPasteState(gate = QuickPasteGate.READY, searched = true))
        compose.onNodeWithTag(QuickPasteTags.KEEP_OPEN).assertIsDisplayed()
        compose.onNodeWithTag(QuickPasteTags.KEEP_OPEN_FOOTER).assertDoesNotExist()
        compose.onNodeWithTag(QuickPasteTags.KEEP_OPEN).performClick()
        compose.runOnIdle { assertEquals(true, keepOpenAfterCopy) }
        compose.onNodeWithTag(QuickPasteTags.KEEP_OPEN).performClick()
        compose.runOnIdle { assertEquals(false, keepOpenAfterCopy) }
    }

    @Test fun narrowButTallWindowUsesSearchTrailingToggle() {
        // On Samsung DeX a narrow user-resized window may still report >320dp of content
        // height. Width must independently choose the compact search icon.
        keepOpenAfterCopy = false
        showAtMinimumSize(
            QuickPasteState(gate = QuickPasteGate.READY, searched = true),
            width = 420.dp,
            height = 620.dp,
        )
        compose.onNodeWithTag(QuickPasteTags.KEEP_OPEN).assertIsDisplayed().performClick()
        compose.onNodeWithTag(QuickPasteTags.KEEP_OPEN_FOOTER).assertDoesNotExist()
        compose.runOnIdle { assertEquals(true, keepOpenAfterCopy) }
    }

    @Test fun normalWindowKeepsTheUserTestedFooterChip() {
        keepOpenAfterCopy = false
        showAtMinimumSize(
            QuickPasteState(gate = QuickPasteGate.READY, searched = true),
            width = 560.dp,
            height = 620.dp,
        )
        compose.onNodeWithTag(QuickPasteTags.KEEP_OPEN_FOOTER).assertIsDisplayed().performClick()
        compose.onNodeWithTag(QuickPasteTags.KEEP_OPEN).assertDoesNotExist()
        compose.runOnIdle { assertEquals(true, keepOpenAfterCopy) }
    }

    @Test fun lockPaneButtonsStayReachableAtTheMinimumHeight() {
        showAtMinimumSize(QuickPasteState(gate = QuickPasteGate.LOCKED,
            message = "Strong biometric authentication is not available on this device right now."))
        compose.onNodeWithText(context.getString(R.string.unlock_vault)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, unlocks)
        compose.onNodeWithTag(QuickPasteTags.ROOT).performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, closed)
    }
}
