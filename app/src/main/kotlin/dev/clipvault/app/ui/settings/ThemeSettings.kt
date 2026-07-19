package dev.clipvault.app.ui.settings

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }

enum class AccentPalette { VIOLET, BLUE, TEAL, ROSE, AMBER, GRAPHITE }

enum class AppLanguage(val languageTag: String) {
    SYSTEM(""), PERSIAN("fa"), ENGLISH("en")
}

fun AppLanguage.isRtl(systemRtl: Boolean): Boolean = when (this) {
    AppLanguage.SYSTEM -> systemRtl
    AppLanguage.PERSIAN -> true
    AppLanguage.ENGLISH -> false
}

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val accentPalette: AccentPalette = AccentPalette.VIOLET,
    val reducedMotion: Boolean = false,
    val language: AppLanguage = AppLanguage.SYSTEM,
    val fontScale: Float = 1f,
)
