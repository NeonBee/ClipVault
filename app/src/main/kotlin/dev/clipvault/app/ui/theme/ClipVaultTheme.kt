package dev.clipvault.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import dev.clipvault.app.ui.settings.AccentPalette
import dev.clipvault.app.ui.settings.ThemeMode
import dev.clipvault.app.ui.settings.ThemeSettings

private data class Accent(val primary: Color, val secondary: Color, val tertiary: Color)

private val accents = mapOf(
    AccentPalette.VIOLET to Accent(Color(0xFF7357D9), Color(0xFF76546F), Color(0xFF8B4F63)),
    AccentPalette.BLUE to Accent(Color(0xFF2962C6), Color(0xFF4D5F80), Color(0xFF356A70)),
    AccentPalette.TEAL to Accent(Color(0xFF006B60), Color(0xFF4A635F), Color(0xFF526343)),
    AccentPalette.ROSE to Accent(Color(0xFF9C405E), Color(0xFF76565E), Color(0xFF7B5734)),
    AccentPalette.AMBER to Accent(Color(0xFF805600), Color(0xFF6D5D3F), Color(0xFF4D6545)),
    AccentPalette.GRAPHITE to Accent(Color(0xFF4F606F), Color(0xFF59616A), Color(0xFF655B68)),
)

@Composable
fun ClipVaultTheme(settings: ThemeSettings, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val decision = resolveTheme(settings, systemDark, Build.VERSION.SDK_INT)
    val dark = decision.dark
    val context = LocalContext.current
    val dynamic = decision.dynamic
    var colors = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        paletteScheme(settings.accentPalette, dark)
    }
    if (settings.mode == ThemeMode.AMOLED) colors = colors.asAmoled()
    val configuration = LocalConfiguration.current
    val baseDensity = configuration.densityDpi / 160f
    val systemFontScale = configuration.fontScale
    CompositionLocalProvider(
        androidx.compose.ui.platform.LocalDensity provides Density(baseDensity, systemFontScale * settings.fontScale)
    ) {
        MaterialTheme(colorScheme = colors, typography = ClipVaultTypography, content = content)
    }
}

data class ThemeDecision(val dark: Boolean, val dynamic: Boolean, val amoled: Boolean)

fun resolveTheme(settings: ThemeSettings, systemDark: Boolean, sdkInt: Int): ThemeDecision {
    val dark = when (settings.mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.SYSTEM -> systemDark
    }
    return ThemeDecision(dark, settings.dynamicColor && sdkInt >= Build.VERSION_CODES.S,
        settings.mode == ThemeMode.AMOLED)
}

private fun paletteScheme(palette: AccentPalette, dark: Boolean): ColorScheme {
    val accent = accents.getValue(palette)
    return if (dark) {
        darkColorScheme(
            primary = accent.primary.lighten(),
            secondary = accent.secondary.lighten(),
            tertiary = accent.tertiary.lighten(),
            background = Color(0xFF111115),
            surface = Color(0xFF19191F),
            surfaceVariant = Color(0xFF25242C),
        )
    } else {
        lightColorScheme(
            primary = accent.primary,
            secondary = accent.secondary,
            tertiary = accent.tertiary,
            background = Color(0xFFF9F7FC),
            surface = Color(0xFFFFFBFF),
            surfaceVariant = Color(0xFFE9E5EF),
        )
    }
}

private fun Color.lighten() = Color(
    red = (red + 0.28f).coerceAtMost(1f),
    green = (green + 0.28f).coerceAtMost(1f),
    blue = (blue + 0.28f).coerceAtMost(1f),
    alpha = alpha,
)

private fun ColorScheme.asAmoled() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceBright = Color(0xFF151515),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF070707),
    surfaceContainer = Color(0xFF0B0B0B),
    surfaceContainerHigh = Color(0xFF111111),
    surfaceContainerHighest = Color(0xFF181818),
)
