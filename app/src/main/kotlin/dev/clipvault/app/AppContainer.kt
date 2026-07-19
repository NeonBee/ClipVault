package dev.clipvault.app

import android.content.Context
import dev.clipvault.app.backup.VaultBackupManager
import dev.clipvault.app.ui.settings.SettingsRepository

class AppContainer(context: Context) {
    val settingsRepository = SettingsRepository(context.applicationContext)
    val backupManager = VaultBackupManager(context.applicationContext)
}
