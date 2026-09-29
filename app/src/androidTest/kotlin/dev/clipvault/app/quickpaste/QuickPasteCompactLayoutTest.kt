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
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.clipvault.app.R
import dev.clipvault.app.data.ClipItem
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** WP-06: the QuickPaste freeform minimum is 280x260dp (manifest <layout>); the keyboard flow must still work there. */
@OptIn(ExperimentalTestApi::class)
class QuickPasteCompactLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var state by mutableStateOf(QuickPasteState())
    private var confirmed: ClipItem? = null
    private var closed = 0
    private var unlocks = 0

    private fun showAtMinimumSize(initial: QuickPasteState) {
        state = initial
        compose.setContent {
            Box(Modifier.size(width = 280.dp, height = 260.dp)) {
                QuickPasteScreen(
                    state = state,
                    onQueryChange = { state = state.copy(query = it) },
                    onKey = { state = state.copy(selected = QuickPasteSelection.move(state.selected, it, state.results.size)) },
                    onSelectIndex = { state = state.copy(selected = it) },
                    onConfirm = { confirmed = state.selectedItem },
                    onClose = { closed++ },
                    onUnlock = { unlocks++ },
                    onOpenApp = {},
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

    @Test fun lockPaneButtonsStayReachableAtTheMinimumHeight() {
        showAtMinimumSize(QuickPasteState(gate = QuickPasteGate.LOCKED,
            message = "Strong biometric authentication is not available on this device right now."))
        compose.onNodeWithText(context.getString(R.string.unlock_vault)).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, unlocks)
        compose.onNodeWithTag(QuickPasteTags.ROOT).performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, closed)
    }
}
