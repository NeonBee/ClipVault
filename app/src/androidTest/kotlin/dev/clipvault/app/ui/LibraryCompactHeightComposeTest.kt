package dev.clipvault.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.R
import dev.clipvault.app.ThemeTestActivity
import dev.clipvault.app.security.VaultKeyManager
import dev.clipvault.app.ui.settings.ThemeSettings
import dev.clipvault.app.ui.theme.ClipVaultTheme
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * WP-06 compact height: a short DeX freeform window collapses quick search into a top-left search
 * affordance. Advanced Search remains a separate action. Uses the throwaway test vault key and is
 * skipped on a device with a real vault.
 */
class LibraryCompactHeightComposeTest {
    @get:Rule val compose = createAndroidComposeRule<ThemeTestActivity>()
    private val app get() = compose.activity.applicationContext as ClipVaultApp
    private var windowHeight by mutableStateOf(800.dp)

    @Before fun openTestVault() {
        assumeFalse("a real vault is provisioned on this device", VaultKeyManager(compose.activity).isProvisioned)
        app.lockVault()
        app.io().submit<Int> { app.openVault(ByteArray(32) { (it + 91).toByte() }) }.get(10, TimeUnit.SECONDS)
    }

    @After fun lock() {
        app.lockVault()
        app.io().submit { }.get(5, TimeUnit.SECONDS)
    }

    @Test fun compactSearchCanExpandFocusAndCollapseWithoutClearingQuery() {
        lateinit var viewModel: VaultViewModel
        compose.runOnUiThread {
            viewModel = ViewModelProvider(compose.activity)[VaultViewModel::class.java]
            viewModel.onVaultOpened(0)
        }
        compose.setContent {
            ClipVaultTheme(ThemeSettings()) {
                Box(Modifier.width(320.dp).height(windowHeight)) {
                    ClipVaultUi(viewModel, ThemeSettings(), shizukuReady = false, captureEnabled = false,
                        onUnlock = {}, onLock = {}, onToggleCapture = {}, onCopy = {}, onExportSelection = {},
                        onExportBackup = {}, onImportBackup = {})
                }
            }
        }

        val advancedSearch = compose.activity.getString(R.string.advanced_search)
        val search = compose.activity.getString(R.string.search)
        val closeSearch = compose.activity.getString(R.string.close_search)
        val trash = compose.activity.getString(R.string.trash)
        val sort = compose.activity.getString(R.string.sort)
        val lock = compose.activity.getString(R.string.lock)

        compose.onNodeWithTag(LIBRARY_INLINE_SEARCH_TAG).assertIsDisplayed()
        compose.onNodeWithTag(LIBRARY_COMPACT_SEARCH_TAG).assertDoesNotExist()

        // 400dp is below the 420dp compact threshold but above the 384dp freeform floor.
        windowHeight = 400.dp
        compose.waitForIdle()
        compose.onNodeWithTag(LIBRARY_INLINE_SEARCH_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription(search).assertIsDisplayed()
        compose.onNodeWithContentDescription(trash).assertIsDisplayed()
        compose.onNodeWithContentDescription(sort).assertIsDisplayed()
        compose.onNodeWithContentDescription(advancedSearch).assertIsDisplayed()
        compose.onNodeWithContentDescription(lock).assertIsDisplayed()

        compose.onNodeWithContentDescription(search).performClick()
        compose.onNodeWithTag(LIBRARY_INLINE_SEARCH_TAG).assertIsDisplayed().assertIsFocused()
        compose.onNodeWithContentDescription(closeSearch).assertIsDisplayed()

        // An active query does not pin the field open; collapsing preserves the filter.
        compose.runOnIdle { viewModel.setSearch("sample") }
        compose.onNodeWithContentDescription(closeSearch).performClick()
        compose.onNodeWithTag(LIBRARY_INLINE_SEARCH_TAG).assertDoesNotExist()

        // Restoring normal height always restores the regular inline search with the query intact.
        windowHeight = 800.dp
        compose.waitForIdle()
        compose.onNodeWithTag(LIBRARY_INLINE_SEARCH_TAG).assertIsDisplayed()
    }
}
