package dev.clipvault.app.securecopy

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.R
import dev.clipvault.app.security.BiometricVaultUnlock
import dev.clipvault.app.security.VaultKeyManager
import dev.clipvault.app.security.VaultLockLog

/**
 * User-invoked text-selection ingress. No payload preview, clipboard write, pending-store fallback,
 * sensitive-content toast, notification, search history or intent re-use.
 *
 * The incoming text must remain in process RAM only until the user confirms/cancels. Strings cannot
 * be forcibly zeroized; clear references promptly and avoid process-death persistence.
 */
class SecureCopyActivity : AppCompatActivity() {
    private enum class Status { READY, AUTHENTICATING, SAVING, INVALID, FAILED }

    private lateinit var app: ClipVaultApp
    private lateinit var unlocker: BiometricVaultUnlock
    private var selectedText: String? = null
    private var status by mutableStateOf(Status.READY)
    @Volatile private var acceptWrite = false
    private var authenticating = false
    private val lockListener = Runnable {
        // Fail closed if a screen-off, explicit lock or keyguard event invalidates the request.
        acceptWrite = false
        selectedText = null
        if (!isFinishing) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        app = application as ClipVaultApp
        // Never restore selection text from a saved instance state after process death/rotation.
        selectedText = if (savedInstanceState == null) SecureCopyRequest.selectedText(intent) else null
        status = if (selectedText == null) Status.INVALID else Status.READY
        intent?.removeExtra(android.content.Intent.EXTRA_PROCESS_TEXT)
        setIntent(android.content.Intent())
        unlocker = BiometricVaultUnlock(this, app, VaultKeyManager(this), unlockListener)
        app.addLockListener(lockListener)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = abort()
        })

        setContent {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(getString(R.string.secure_copy_title), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        getString(
                            if (status == Status.INVALID) R.string.secure_copy_invalid
                            else if (status == Status.FAILED) R.string.secure_copy_failed
                            else R.string.secure_copy_explanation,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (status == Status.READY && selectedText != null) {
                        Button(onClick = ::confirmSave) {
                            Text(getString(R.string.secure_copy_save))
                        }
                    } else if (status == Status.AUTHENTICATING || status == Status.SAVING) {
                        Text(getString(R.string.secure_copy_working))
                    }
                    OutlinedButton(onClick = ::abort) { Text(getString(R.string.cancel)) }
                }
            }
        }
    }

    private fun confirmSave() {
        if (status != Status.READY || selectedText == null) return
        if (getSystemService(KeyguardManager::class.java).isDeviceLocked) {
            app.lockVault(VaultLockLog.Reason.DEVICE_LOCKED_MAIN)
            abort()
            return
        }
        if (app.isUnlocked) {
            writeToVault()
        } else {
            authenticating = true
            status = Status.AUTHENTICATING
            unlocker.start(allowEnrollment = false)
        }
    }

    private fun writeToVault() {
        val payload = selectedText ?: return abort()
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return abort()
        selectedText = null
        authenticating = false
        status = Status.SAVING
        acceptWrite = true
        val epoch = app.lockEpoch()
        if (!SecureCopyGate.canCommit(app.isUnlocked, epoch, app.lockEpoch())) {
            acceptWrite = false
            status = Status.FAILED
            return
        }
        app.io().execute {
            // Single app-wide IO executor serializes this write with vault open/close. Never
            // call ClipVaultApp.capture(), which can write to staging even when locked.
            val saved = runCatching {
                if (!acceptWrite) false else app.saveExplicitSecureText(payload, epoch)
            }.getOrDefault(false)
            runOnUiThread {
                acceptWrite = false
                if (!isFinishing) {
                    if (saved) {
                        Toast.makeText(applicationContext, R.string.secure_copy_saved, Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        status = Status.FAILED
                    }
                }
            }
        }
    }

    private val unlockListener = object : BiometricVaultUnlock.Listener {
        override fun onUnavailable(availability: Int) = fail()
        override fun onNotProvisioned() = fail()
        override fun onKeyInvalidated() = fail()
        override fun onAuthenticationError(code: Int, message: CharSequence) = abort()
        override fun onAuthenticationFailed() = Unit
        override fun onOpening() = Unit
        override fun onOpened(imported: Int) {
            authenticating = false
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) abort()
            else writeToVault()
        }
        override fun onFailure(message: String) = fail()
    }

    private fun fail() {
        authenticating = false
        selectedText = null
        acceptWrite = false
        status = Status.FAILED
    }

    private fun abort() {
        authenticating = false
        acceptWrite = false
        selectedText = null
        if (!isFinishing) finish()
    }

    override fun onStop() {
        super.onStop()
        // BiometricPrompt can temporarily obscure the Activity; wait for its callback but
        // refuse to write if we are no longer STARTED. All other backgrounding drops the text.
        if (!authenticating && !isFinishing) abort()
    }

    override fun onDestroy() {
        acceptWrite = false
        selectedText = null
        app.removeLockListener(lockListener)
        super.onDestroy()
    }
}
