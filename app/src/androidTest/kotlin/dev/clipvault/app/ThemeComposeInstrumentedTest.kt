package dev.clipvault.app

import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import dev.clipvault.app.ui.settings.AccentPalette
import dev.clipvault.app.ui.settings.ThemeMode
import dev.clipvault.app.ui.settings.ThemeSettings
import dev.clipvault.app.ui.theme.ClipVaultTheme
import org.junit.Rule
import org.junit.Test

class ThemeComposeInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ThemeTestActivity>()

    @Test fun systemThemeRenders() = assertTheme(ThemeMode.SYSTEM, AccentPalette.VIOLET, 1f)
    @Test fun lightThemeRenders() = assertTheme(ThemeMode.LIGHT, AccentPalette.BLUE, 1.15f)
    @Test fun darkThemeRenders() = assertTheme(ThemeMode.DARK, AccentPalette.TEAL, 1.30f)
    @Test fun amoledThemeRenders() = assertTheme(ThemeMode.AMOLED, AccentPalette.ROSE, .85f)

    private fun assertTheme(mode: ThemeMode, accent: AccentPalette, fontScale: Float) {
        compose.setContent {
            ClipVaultTheme(ThemeSettings(mode, false, accent, fontScale = fontScale)) {
                Text("theme-preview", Modifier.testTag("theme-preview"))
            }
        }
        compose.onNodeWithTag("theme-preview").assertIsDisplayed()
    }
}
