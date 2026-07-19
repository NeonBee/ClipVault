package dev.clipvault.app.ui.theme

import dev.clipvault.app.ui.settings.ThemeMode
import dev.clipvault.app.ui.settings.ThemeSettings
import dev.clipvault.app.ui.settings.AccentPalette
import dev.clipvault.app.ui.settings.AppLanguage
import dev.clipvault.app.ui.settings.isRtl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeResolverTest {
    @Test fun systemFollowsDeviceAndDynamicNeedsAndroid12() {
        assertFalse(resolveTheme(ThemeSettings(), systemDark = false, sdkInt = 30).dynamic)
        val modern = resolveTheme(ThemeSettings(), systemDark = true, sdkInt = 31)
        assertTrue(modern.dark)
        assertTrue(modern.dynamic)
    }

    @Test fun amoledAlwaysUsesDarkSurfaces() {
        val result = resolveTheme(ThemeSettings(mode = ThemeMode.AMOLED), systemDark = false, sdkInt = 35)
        assertTrue(result.dark)
        assertTrue(result.amoled)
    }

    @Test fun everyModeAndAccentResolvesDeterministically() {
        ThemeMode.entries.forEach { mode ->
            AccentPalette.entries.forEach { accent ->
                val result = resolveTheme(ThemeSettings(mode = mode, accentPalette = accent), false, 30)
                assertEquals(mode == ThemeMode.DARK || mode == ThemeMode.AMOLED, result.dark)
                assertFalse(result.dynamic)
            }
        }
    }

    @Test fun explicitLanguagesOverrideSystemLayoutDirection() {
        assertTrue(AppLanguage.PERSIAN.isRtl(systemRtl = false))
        assertFalse(AppLanguage.ENGLISH.isRtl(systemRtl = true))
        assertTrue(AppLanguage.SYSTEM.isRtl(systemRtl = true))
    }
}
