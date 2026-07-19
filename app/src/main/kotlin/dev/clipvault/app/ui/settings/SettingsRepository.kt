package dev.clipvault.app.ui.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.clipVaultDataStore by preferencesDataStore(name = "clipvault_ui_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val themeMode = stringPreferencesKey("theme_mode")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val accent = stringPreferencesKey("accent_palette")
        val reducedMotion = booleanPreferencesKey("reduced_motion")
        val language = stringPreferencesKey("app_language")
        val fontScale = floatPreferencesKey("font_scale")
    }

    val settings: Flow<ThemeSettings> = context.clipVaultDataStore.data
        .catch { error ->
            if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
        }
        .map { preferences ->
            ThemeSettings(
                mode = preferences[Keys.themeMode].toEnumOrDefault(ThemeMode.SYSTEM),
                dynamicColor = preferences[Keys.dynamicColor] ?: true,
                accentPalette = preferences[Keys.accent].toEnumOrDefault(AccentPalette.VIOLET),
                reducedMotion = preferences[Keys.reducedMotion] ?: false,
                language = preferences[Keys.language].toEnumOrDefault(AppLanguage.SYSTEM),
                fontScale = (preferences[Keys.fontScale] ?: 1f).coerceIn(0.85f, 1.30f),
            )
        }

    suspend fun setThemeMode(value: ThemeMode) = context.clipVaultDataStore.edit {
        it[Keys.themeMode] = value.name
    }

    suspend fun setDynamicColor(value: Boolean) = context.clipVaultDataStore.edit {
        it[Keys.dynamicColor] = value
    }

    suspend fun setAccent(value: AccentPalette) = context.clipVaultDataStore.edit {
        it[Keys.accent] = value.name
    }

    suspend fun setReducedMotion(value: Boolean) = context.clipVaultDataStore.edit {
        it[Keys.reducedMotion] = value
    }

    suspend fun setLanguage(value: AppLanguage) = context.clipVaultDataStore.edit {
        it[Keys.language] = value.name
    }

    suspend fun setFontScale(value: Float) = context.clipVaultDataStore.edit {
        it[Keys.fontScale] = value.coerceIn(0.85f, 1.30f)
    }

    private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T =
        this?.let { value -> enumValues<T>().firstOrNull { it.name == value } } ?: default
}
