package dev.clipvault.app

import android.Manifest
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import dev.clipvault.app.backup.VaultBackupManager
import dev.clipvault.app.clipboard.ClipboardCaptureService
import dev.clipvault.app.clipboard.SensitiveClipboard
import dev.clipvault.app.clipboard.ShizukuController
import dev.clipvault.app.data.ClipItem
import dev.clipvault.app.quickpaste.QuickPasteShortcut
import dev.clipvault.app.security.BiometricVaultUnlock
import dev.clipvault.app.security.VaultKeyManager
import dev.clipvault.app.security.VaultLockLog
import dev.clipvault.app.ui.ClipVaultUi
import dev.clipvault.app.ui.VaultViewModel
import dev.clipvault.app.ui.settings.AppLanguage
import dev.clipvault.app.ui.settings.ThemeSettings
import dev.clipvault.app.ui.theme.ClipVaultTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {
    companion object {
        private const val SHIZUKU_PERMISSION_REQUEST = 42
    }

    private val viewModel: VaultViewModel by viewModels()
    private lateinit var app: ClipVaultApp
    private lateinit var keyManager: VaultKeyManager
    private lateinit var unlocker: BiometricVaultUnlock
    private var shizukuReady by mutableStateOf(false)
    private var captureEnabled by mutableStateOf(false)
    private var pendingExportPassphrase: CharArray? = null

    // Auto-lock and screen-off locking live in ClipVaultApp so every ClipVault window shares them.
    private val vaultLockListener = Runnable { viewModel.onVaultLocked() }
    private val dataChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = viewModel.refresh()
    }
    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { updateCaptureState() }
    private val shizukuDeadListener = Shizuku.OnBinderDeadListener { updateCaptureState() }
    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, result ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST) runOnUiThread {
            updateCaptureState()
            if (result == PackageManager.PERMISSION_GRANTED) requestNotificationAndStart()
        }
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted || Build.VERSION.SDK_INT < 33) startCaptureService()
    }
    private val createBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val passphrase = pendingExportPassphrase.also { pendingExportPassphrase = null } ?: return@registerForActivityResult
        if (uri == null) { passphrase.fill('\u0000'); return@registerForActivityResult }
        exportBackup(uri, passphrase)
    }
    private val openBackup = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) showImportPassphraseDialog(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        app = application as ClipVaultApp
        keyManager = VaultKeyManager(this)
        unlocker = BiometricVaultUnlock(this, app, keyManager, unlockListener)
        app.addLockListener(vaultLockListener)
        ContextCompat.registerReceiver(this, dataChangedReceiver,
            IntentFilter(ClipVaultApp.ACTION_DATA_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        QuickPasteShortcut.publish(this)
        registerShizukuListeners()
        updateCaptureState()
        handleIncomingText(intent)

        setContent {
            val theme by viewModel.themeSettings.collectAsState(initial = ThemeSettings())
            LaunchedEffect(theme.language) { applyLanguage(theme.language) }
            ClipVaultTheme(theme) {
                ClipVaultUi(
                    viewModel = viewModel,
                    themeSettings = theme,
                    shizukuReady = shizukuReady,
                    captureEnabled = captureEnabled,
                    onUnlock = ::beginBiometricUnlock,
                    onLock = { app.lockVault(VaultLockLog.Reason.USER) },
                    onToggleCapture = ::toggleCapture,
                    onCopy = ::copyToClipboard,
                    onExportSelection = ::shareText,
                    onExportBackup = ::showExportPassphraseDialog,
                    onImportBackup = { openBackup.launch(arrayOf("application/octet-stream", "application/*")) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingText(intent)
    }

    private fun handleIncomingText(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            else -> null
        }?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            app.capture(text, System.currentTimeMillis())
            intent?.action = null
        }
    }

    private fun beginBiometricUnlock() = unlocker.start(allowEnrollment = true)

    private val unlockListener = object : BiometricVaultUnlock.Listener {
        override fun onUnavailable(availability: Int) {
            viewModel.setError(getString(R.string.biometric_unavailable))
            if (availability == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED && Build.VERSION.SDK_INT >= 30) {
                runCatching {
                    startActivity(Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
                        Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        BiometricManager.Authenticators.BIOMETRIC_STRONG))
                }.onFailure { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
            }
        }
        override fun onNotProvisioned() = Unit // enrollment is allowed here
        override fun onKeyInvalidated() = showVaultResetDialog()
        override fun onAuthenticationError(code: Int, message: CharSequence) = viewModel.setError(message.toString())
        override fun onAuthenticationFailed() = viewModel.setError(getString(R.string.biometric_failed))
        override fun onOpening() = viewModel.setBusy(true)
        override fun onOpened(imported: Int) { viewModel.onVaultOpened(imported); restartCaptureIfEnabled() }
        override fun onFailure(message: String) = viewModel.setError(message)
    }

    private fun toggleCapture() {
        if (captureEnabled) {
            stopService(Intent(this, ClipboardCaptureService::class.java).setAction(ClipboardCaptureService.ACTION_STOP))
            app.settings().edit().putBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false).apply()
            updateCaptureState()
            return
        }
        if (!ShizukuController.isBinderRunning()) {
            viewModel.setError(getString(R.string.shizuku_start_required))
            return
        }
        if (!ShizukuController.hasPermission()) {
            runCatching { Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST) }
                .onFailure { viewModel.setError(getString(R.string.capture_permission_needed)) }
            return
        }
        requestNotificationAndStart()
    }

    private fun requestNotificationAndStart() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else startCaptureService()
    }

    private fun startCaptureService() {
        ContextCompat.startForegroundService(this,
            Intent(this, ClipboardCaptureService::class.java).setAction(ClipboardCaptureService.ACTION_START))
        app.settings().edit().putBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, true).apply()
        updateCaptureState()
    }

    private fun restartCaptureIfEnabled() {
        if (app.settings().getBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false) && ShizukuController.hasPermission()) {
            startCaptureService()
        }
    }

    private fun updateCaptureState() {
        shizukuReady = ShizukuController.hasPermission()
        captureEnabled = app.settings().getBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false)
    }

    private fun copyToClipboard(item: ClipItem) {
        SensitiveClipboard.write(this, item.content)
        Toast.makeText(this, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.export_selected)))
    }

    private fun showVaultResetDialog() {
        val confirmation = EditText(this).apply { hint = "RESET" }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.key_invalidated_title)
            .setMessage(R.string.key_invalidated)
            .setView(confirmation)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.reset_vault, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (confirmation.text?.toString() != "RESET") {
                    confirmation.error = getString(R.string.type_reset)
                    return@setOnClickListener
                }
                dialog.dismiss()
                viewModel.setBusy(true)
                app.io().execute {
                    runCatching {
                        keyManager.destroyBiometricEnvelope()
                        app.resetVaultFiles()
                    }.onSuccess {
                        runOnUiThread {
                            viewModel.onVaultLocked()
                            Toast.makeText(this, R.string.vault_reset_complete, Toast.LENGTH_LONG).show()
                        }
                    }.onFailure { error ->
                        runOnUiThread { viewModel.setError(error.message ?: getString(R.string.reset_failed)) }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showExportPassphraseDialog() {
        val (container, first, confirm) = passwordFields()
        AlertDialog.Builder(this).setTitle(R.string.backup_passphrase_title).setView(container)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.export_backup) { _, _ ->
                val left = first.text.toString(); val right = confirm.text.toString()
                if (left.length < VaultBackupManager.MIN_PASSPHRASE_LENGTH || left != right) {
                    viewModel.setError(getString(R.string.backup_passphrase_error))
                } else {
                    pendingExportPassphrase = left.toCharArray()
                    createBackup.launch("clipvault-${System.currentTimeMillis()}.cvault")
                }
            }.show()
    }

    private fun showImportPassphraseDialog(uri: Uri) {
        val field = EditText(this).apply {
            hint = getString(R.string.passphrase)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this).setTitle(R.string.import_backup).setView(field)
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.replace) { _, _ -> prepareImport(uri, field.text.toString().toCharArray(), true) }
            .setPositiveButton(R.string.merge) { _, _ -> prepareImport(uri, field.text.toString().toCharArray(), false) }
            .show()
    }

    private fun prepareImport(uri: Uri, passphrase: CharArray, replace: Boolean) {
        if (passphrase.size < VaultBackupManager.MIN_PASSPHRASE_LENGTH) {
            passphrase.fill('\u0000')
            viewModel.setError(getString(R.string.backup_passphrase_error))
            return
        }
        viewModel.setBusy(true)
        lifecycleScope.launch {
            runCatching { app.container().backupManager.inspect(uri, passphrase.copyOf()) }
                .onSuccess { manifest ->
                    viewModel.setBusy(false)
                    val confirmation = if (replace) EditText(this@MainActivity).apply {
                        hint = getString(R.string.type_replace)
                    } else null
                    val dialog = AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.import_preview)
                        .setMessage(getString(R.string.import_preview_counts, manifest.clipCount,
                            manifest.collectionCount, manifest.tagCount, manifest.ruleCount))
                        .apply { if (confirmation != null) setView(confirmation) }
                        .setNegativeButton(R.string.cancel) { _, _ -> passphrase.fill('\u0000') }
                        .setPositiveButton(if (replace) R.string.replace else R.string.merge, null)
                        .create()
                    dialog.setOnCancelListener { passphrase.fill('\u0000') }
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                            if (replace && confirmation?.text?.toString() != "REPLACE") {
                                confirmation?.error = getString(R.string.type_replace)
                            } else {
                                dialog.dismiss()
                                if (replace) reauthenticateForReplace(passphrase) {
                                    importBackup(uri, passphrase, true)
                                } else importBackup(uri, passphrase, false)
                            }
                        }
                    }
                    dialog.show()
                }.onFailure {
                    passphrase.fill('\u0000')
                    viewModel.setError(it.message ?: getString(R.string.backup_failed))
                }
        }
    }

    private fun reauthenticateForReplace(passphrase: CharArray, action: () -> Unit) {
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = action()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    passphrase.fill('\u0000'); viewModel.setError(errString.toString())
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.replace_auth_title))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.cancel))
            .build()
        prompt.authenticate(info)
    }

    private fun exportBackup(uri: Uri, passphrase: CharArray) {
        val repository = app.repository() ?: run { passphrase.fill('\u0000'); return }
        viewModel.setBusy(true)
        lifecycleScope.launch {
            runCatching { app.container().backupManager.exportVault(uri, passphrase, repository) }
                .onSuccess { viewModel.setBusy(false); viewModel.refresh(); Toast.makeText(this@MainActivity, R.string.backup_complete, Toast.LENGTH_LONG).show() }
                .onFailure { viewModel.setError(it.message ?: getString(R.string.backup_failed)) }
        }
    }

    private fun importBackup(uri: Uri, passphrase: CharArray, replace: Boolean) {
        val repository = app.repository() ?: run { passphrase.fill('\u0000'); return }
        viewModel.setBusy(true)
        lifecycleScope.launch {
            runCatching { app.container().backupManager.importVault(uri, passphrase, repository, replace) }
                .onSuccess { result ->
                    viewModel.setBusy(false); viewModel.refresh()
                    Toast.makeText(this@MainActivity, getString(R.string.import_complete, result.importedClips), Toast.LENGTH_LONG).show()
                }.onFailure { viewModel.setError(it.message ?: getString(R.string.backup_failed)) }
        }
    }

    private fun passwordFields(): Triple<LinearLayout, EditText, EditText> {
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0) }
        val first = EditText(this).apply { hint = getString(R.string.passphrase); inputType = 129 }
        val confirm = EditText(this).apply { hint = getString(R.string.confirm_passphrase); inputType = 129 }
        container.addView(first); container.addView(confirm)
        return Triple(container, first, confirm)
    }

    private fun applyLanguage(language: AppLanguage) {
        val desired = LocaleListCompat.forLanguageTags(language.languageTag)
        if (AppCompatDelegate.getApplicationLocales() != desired) AppCompatDelegate.setApplicationLocales(desired)
    }

    private fun registerShizukuListeners() {
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onResume() {
        super.onResume()
        updateCaptureState()
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) app.lockVault(VaultLockLog.Reason.DEVICE_LOCKED_MAIN)
        // The vault may have been unlocked from QuickPaste while this window was in the background.
        if (app.isUnlocked) viewModel.onVaultAvailable() else viewModel.onVaultLocked()
    }

    override fun onDestroy() {
        app.removeLockListener(vaultLockListener)
        unregisterReceiver(dataChangedReceiver)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }
}
