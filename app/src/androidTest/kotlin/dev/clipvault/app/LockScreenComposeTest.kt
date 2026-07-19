package dev.clipvault.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class LockScreenComposeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun lockedVaultShowsUnlockAction() {
        compose.onNodeWithText(compose.activity.getString(R.string.unlock_vault)).assertIsDisplayed()
    }
}
