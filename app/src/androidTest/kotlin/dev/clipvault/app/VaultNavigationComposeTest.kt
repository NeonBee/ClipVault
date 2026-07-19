package dev.clipvault.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalTestApi::class)
class VaultNavigationComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app get() = context.applicationContext as ClipVaultApp

    @Before fun unlockTestVault() {
        app.lockVault()
        app.io().submit {}.get(5, TimeUnit.SECONDS)
        context.deleteDatabase("clipvault.db")
        app.io().submit { app.openVault(ByteArray(32) { (it + 91).toByte() }) }.get(10, TimeUnit.SECONDS)
        assertNotNull(app.repository())
        app.repository()!!.insert("compose navigation sample", System.currentTimeMillis())
    }

    @After fun closeTestVault() {
        app.lockVault()
        app.io().submit {}.get(5, TimeUnit.SECONDS)
        context.deleteDatabase("clipvault.db")
    }

    @Test fun navigationMultiSelectAndTrashRestoreWork() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val settings = context.getString(R.string.settings)
            compose.onNodeWithText(settings).performClick()
            compose.onNodeWithText(context.getString(R.string.appearance)).assertIsDisplayed()

            compose.onNodeWithText(context.getString(R.string.library)).performClick()
            compose.waitUntilAtLeastOneExists(hasText("compose navigation sample"), 8_000)
            compose.onNodeWithText("compose navigation sample").performTouchInput { longClick() }
            compose.onNodeWithText(context.getString(R.string.selected_count, 1)).assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.more_actions)).performClick()
            compose.onNodeWithText(context.getString(R.string.move_to_collection)).assertIsDisplayed()
            compose.onNodeWithContentDescription(context.getString(R.string.cancel)).performClick()

            compose.onNodeWithContentDescription(context.getString(R.string.delete)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.trash)).performClick()
            compose.waitUntilAtLeastOneExists(hasText("compose navigation sample"), 8_000)
            compose.onNodeWithText(context.getString(R.string.restore)).performClick()
            compose.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
            compose.waitUntilAtLeastOneExists(hasText("compose navigation sample"), 8_000)
        }
    }
}
