package dev.clipvault.app.quickpaste

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.test.performScrollToIndex
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


    private fun showAtMinimumSize(
        initial: QuickPasteState,
        width: Dp = 240.dp,
        height: Dp = 260.dp,
        syntheticDeXWindow: Boolean = false,
    ) {
        state = initial
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides null) {
                // CI's phone viewport is narrower than a full DeX window. Only the
                // normal-window *branch existence* test uses requiredSize so the layout
                // receives its requested DeX width even if part lies off the host screen.
                val windowModifier = if (syntheticDeXWindow) {
                    Modifier.requiredSize(width = width, height = height)
                } else {
                    Modifier.size(width = width, height = height)
                }
                Box(windowModifier) {
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

        // Keyboard selection and a tiny viewport's scroll affordance are independent.
        // Rapid key injection may settle before listState's LaunchedEffect scroll runs on CI;
        // verify selection first, then deterministically scroll the lazy list into view.
        search.performKeyInput { repeat(4) { pressKey(Key.DirectionDown) } }
        compose.runOnIdle { assertEquals(4, state.selected) }
        compose.onNodeWithTag(QuickPasteTags.LIST).performScrollToIndex(4)
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
            syntheticDeXWindow = true,
        )
        // The synthetic 560×620dp window extends past the CI phone's visible viewport.
        // Verify the expected normal-size UI branch exists rather than requiring the
        // off-screen footer to be physically visible/clickable on a non-DeX emulator.
        compose.onNodeWithTag(QuickPasteTags.KEEP_OPEN_FOOTER).assertExists()
        compose.onNodeWithTag(QuickPasteTags.KEEP_OPEN).assertDoesNotExist()
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
