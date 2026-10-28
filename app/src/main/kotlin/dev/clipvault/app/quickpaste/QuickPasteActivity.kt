package dev.clipvault.app.quickpaste

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.MainActivity
import dev.clipvault.app.R
import dev.clipvault.app.clipboard.SensitiveClipboard
import dev.clipvault.app.security.BiometricVaultUnlock
import dev.clipvault.app.security.VaultKeyManager
import dev.clipvault.app.security.VaultLockLog
import dev.clipvault.app.ui.settings.ThemeSettings
import dev.clipvault.app.ui.theme.ClipVaultTheme

/**
 * Compact search-and-copy window for DeX / hardware keyboards (design §16, §18).
 *
 * - FLAG_SECURE and no recents thumbnail; the task is excluded from recents.
 * - A locked vault goes through the same BiometricPrompt + CryptoObject unlock as the main app.
 *   There is no plaintext cache: without an open vault this window shows nothing from it.
 * - The query and results live in the ViewModel only and are cleared on close or lock.
 * - Enter restores the selected clip with EXTRA_IS_SENSITIVE and closes by default; an
 *   opt-in setting allows successive copies without closing. No auto-paste:
 *   the user returns to the original app and presses Ctrl+V.
 */
class QuickPasteActivity : AppCompatActivity() {
    private val viewModel: QuickPasteViewModel by viewModels()
    private lateinit var app: ClipVaultApp
    private lateinit var unlocker: BiometricVaultUnlock
    /** True while a biometric prompt or the database open is in flight; stopping then must not close us. */
    private var authenticating = false
    private var keepOpenAfterCopy by mutableStateOf(false)
    private val lockListener = Runnable { viewModel.onVaultLocked() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        enableEdgeToEdge()
        app = application as ClipVaultApp
        keepOpenAfterCopy = app.settings().getBoolean(ClipVaultApp.PREF_QUICK_PASTE_KEEP_OPEN, false)
        unlocker = BiometricVaultUnlock(this, app, VaultKeyManager(this), unlockListener)
        app.addLockListener(lockListener)
        onBackPressedDispatcher.addCallback(this) { close() }

        setContent {
            val theme by app.container().settingsRepository.settings.collectAsState(initial = ThemeSettings())
            val state by viewModel.state.collectAsState()
            ClipVaultTheme(theme) {
                QuickPasteScreen(
                    state = state,
                    onQueryChange = viewModel::setQuery,
                    onKey = viewModel::onKey,
                    onSelectIndex = viewModel::select,
                    onConfirm = ::copySelected,
                    onClose = ::close,
                    onUnlock = ::startUnlock,
                    onOpenApp = ::openMainApp,
                    keepOpenAfterCopy = keepOpenAfterCopy,
                    onKeepOpenChange = ::updateKeepOpenPreference,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Relaunching the shortcut starts a fresh search instead of restoring the previous one.
        viewModel.setQuery("")
    }

    override fun onStart() {
        super.onStart()
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) app.lockVault(VaultLockLog.Reason.DEVICE_LOCKED_QUICK_PASTE)
        if (app.isUnlocked) viewModel.onVaultOpen()
        else if (viewModel.state.value.gate == QuickPasteGate.READY) viewModel.onVaultLocked()
    }

    override fun onResume() {
        super.onResume()
        if (!app.isUnlocked && viewModel.state.value.gate == QuickPasteGate.LOCKED && !viewModel.autoPromptConsumed) {
            viewModel.autoPromptConsumed = true
            startUnlock()
        }
    }

    override fun onStop() {
        super.onStop()
        // DeX may minimize QuickPaste before the system shortcut brings it back. Finishing the
        // task here discards the user's freeform window bounds, so let Android retain the task.
        // Never retain decrypted search results or a query while the window is hidden.
        // Esc, explicit Close, Enter/copy and Open ClipVault still call close(), removing the task.
        if (!isChangingConfigurations && !authenticating && !isFinishing) {
            viewModel.clear()
            // If the vault locks while minimized, the next explicit launch must request
            // biometrics again rather than leaving a stale LOCKED screen with no auto-prompt.
            viewModel.autoPromptConsumed = false
        }
    }

    override fun onDestroy() {
        app.removeLockListener(lockListener)
        super.onDestroy()
    }

    private fun startUnlock() {
        if (authenticating || app.isUnlocked) {
            if (app.isUnlocked) viewModel.onVaultOpen()
            return
        }
        authenticating = true
        unlocker.start(allowEnrollment = false)
    }

    private val unlockListener = object : BiometricVaultUnlock.Listener {
        override fun onUnavailable(availability: Int) = settle { viewModel.onVaultLocked(getString(R.string.biometric_unavailable)) }
        override fun onNotProvisioned() = settle { viewModel.onSetupRequired(getString(R.string.quick_paste_setup_required)) }
        override fun onKeyInvalidated() = settle { viewModel.onSetupRequired(getString(R.string.quick_paste_key_invalidated)) }
        override fun onAuthenticationError(code: Int, message: CharSequence) = settle {
            when (code) {
                BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON -> close()
                else -> viewModel.onVaultLocked(message.toString())
            }
        }
        override fun onAuthenticationFailed() = Unit // the system prompt shows its own retry hint
        override fun onOpening() = viewModel.onUnlocking()
        override fun onOpened(imported: Int) = settle { viewModel.onVaultOpen() }
        override fun onFailure(message: String) = settle { viewModel.onVaultLocked(message) }
    }

    /** Ends an unlock attempt; if the window was hidden meanwhile, close it now that onStop skipped it. */
    private fun settle(update: () -> Unit) {
        authenticating = false
        update()
        if (!isFinishing && !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) close()
    }

    private fun updateKeepOpenPreference(enabled: Boolean) {
        app.settings().edit().putBoolean(ClipVaultApp.PREF_QUICK_PASTE_KEEP_OPEN, enabled).apply()
        keepOpenAfterCopy = enabled
    }

    private fun copySelected() {
        val item = viewModel.state.value.selectedItem ?: return
        if (!app.isUnlocked) {
            viewModel.onVaultLocked()
            return
        }
        SensitiveClipboard.write(this, item.content)
        if (!SensitiveClipboard.systemShowsConfirmation) {
            Toast.makeText(applicationContext, R.string.clipboard_copied, Toast.LENGTH_SHORT).show()
        }
        if (QuickPasteCopyPolicy.shouldCloseAfterCopy(keepOpenAfterCopy)) close()
    }

    private fun openMainApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        close()
    }

    private fun close() {
        viewModel.clear()
        if (!isFinishing) finishAndRemoveTask()
    }
}
