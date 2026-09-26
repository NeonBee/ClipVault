package dev.clipvault.app.quickpaste

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import dev.clipvault.app.R
import dev.clipvault.app.data.ClipItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class QuickPasteScreenComposeTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clips = listOf(clip(1, "alpha first"), clip(2, "bravo second"), clip(3, "charlie third"))

    private var state by mutableStateOf(QuickPasteState())
    private var queries = mutableListOf<String>()
    private var confirmed: ClipItem? = null
    private var closed = 0
    private var unlocks = 0
    private var openApp = 0

    private fun show(initial: QuickPasteState) {
        state = initial
        compose.setContent {
            QuickPasteScreen(
                state = state,
                onQueryChange = { queries += it; state = state.copy(query = it) },
                onKey = { state = state.copy(selected = QuickPasteSelection.move(state.selected, it, state.results.size)) },
                onSelectIndex = { state = state.copy(selected = it) },
                onConfirm = { confirmed = state.selectedItem },
                onClose = { closed++ },
                onUnlock = { unlocks++ },
                onOpenApp = { openApp++ },
            )
        }
    }

    @Test fun searchFieldIsFocusedAndArrowsEnterEscDriveTheList() {
        show(QuickPasteState(gate = QuickPasteGate.READY, results = clips, searched = true))
        val search = compose.onNodeWithTag(QuickPasteTags.SEARCH)
        search.assertIsFocused()
        compose.onNodeWithTag(QuickPasteTags.row(0)).assertIsSelected()

        search.performKeyInput { pressKey(Key.DirectionDown); pressKey(Key.DirectionDown); pressKey(Key.DirectionDown) }
        compose.onNodeWithTag(QuickPasteTags.row(2)).assertIsSelected()
        search.performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithTag(QuickPasteTags.row(1)).assertIsSelected()
        compose.onNodeWithTag(QuickPasteTags.row(2)).assertIsNotSelected()

        search.performKeyInput { pressKey(Key.Enter) }
        assertEquals(2L, confirmed?.id)

        search.performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, closed)
    }

    @Test fun typingGoesToTheQueryNotTheList() {
        show(QuickPasteState(gate = QuickPasteGate.READY, results = clips, searched = true))
        compose.onNodeWithTag(QuickPasteTags.SEARCH).performTextInput("bra")
        assertEquals("bra", queries.last())
        assertNull(confirmed)
    }

    @Test fun pointerClickSelectsThenCopiesTheSelectedRow() {
        show(QuickPasteState(gate = QuickPasteGate.READY, results = clips, searched = true))
        compose.onNodeWithTag(QuickPasteTags.row(2)).performClick()
        compose.onNodeWithTag(QuickPasteTags.row(2)).assertIsSelected()
        assertNull(confirmed)
        compose.onNodeWithTag(QuickPasteTags.row(2)).performClick()
        assertEquals(3L, confirmed?.id)
    }

    @Test fun emptyVaultAndNoMatchAreDistinguished() {
        show(QuickPasteState(gate = QuickPasteGate.READY, searched = true))
        compose.onNodeWithText(context.getString(R.string.quick_paste_empty)).assertIsDisplayed()
        state = state.copy(query = "zzz")
        compose.onNodeWithText(context.getString(R.string.quick_paste_no_match)).assertIsDisplayed()
    }

    @Test fun lockedVaultShowsNoClipContentOnlyUnlockAndClose() {
        show(QuickPasteState(gate = QuickPasteGate.LOCKED, message = "try again"))
        compose.onNodeWithTag(QuickPasteTags.SEARCH).assertDoesNotExist()
        compose.onNodeWithTag(QuickPasteTags.LIST).assertDoesNotExist()
        compose.onNodeWithText("try again").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.unlock_vault)).performClick()
        assertEquals(1, unlocks)
    }

    @Test fun lockedPaneTakesKeyboardFocusSoEnterUnlocksAndEscCloses() {
        show(QuickPasteState(gate = QuickPasteGate.LOCKED))
        // No field or button is focused here (buttons are not focusable in touch mode); the root is.
        val root = compose.onNodeWithTag(QuickPasteTags.ROOT)
        root.assertIsFocused()
        root.performKeyInput { pressKey(Key.Enter) }
        assertEquals(1, unlocks)
        assertNull(confirmed)
        root.performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, closed)
    }

    @Test fun setupRequiredSendsTheUserToTheMainApp() {
        show(QuickPasteState(gate = QuickPasteGate.SETUP_REQUIRED, message = "setup"))
        compose.onNodeWithText(context.getString(R.string.quick_paste_open_app)).performClick()
        assertEquals(1, openApp)
    }

    private fun clip(id: Long, content: String) = ClipItem(
        id, content, "", "", "", 0L, System.currentTimeMillis() - id * 60_000L, 1, content.length, 0,
        false, false, null, null)
}
