package dev.clipvault.app.securepaste

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.Lifecycle
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.R
import dev.clipvault.app.security.BiometricVaultUnlock
import dev.clipvault.app.security.VaultKeyManager

/**
 * An invisible, foreground FragmentActivity HOST for the real system biometric prompt.
 *
 * AndroidX BiometricPrompt cannot be owned by InputMethodService. The transparent,
 * non-dimming theme removes the extra full-screen ClipVault page so the native prompt
 * visually floats over the original editor/IME. Some OEMs may still hide the IME when
 * this Activity takes focus; the IME must reacquire its editor before any insertion.
 *
 * Neither editor handles nor selected/decrypted plaintext cross this boundary.
 */
class SecurePasteUnlockActivity : AppCompatActivity() {
    private lateinit var app: ClipVaultApp
    private lateinit var unlocker: BiometricVaultUnlock
    private var started = false
    private var authenticating = false
    private val lockListener = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        // Keep the foreground BiometricPrompt host effectively invisible instead
        // of showing the old full-screen explanation and Cancel button.
        setContentView(View(this))
        app = application as ClipVaultApp
        app.addLockListener(lockListener)
        unlocker = BiometricVaultUnlock(this, app, VaultKeyManager(this), listener)
    }

    override fun onStart() {
        super.onStart()
        // Real system authentication UI is independent of the host's dimensions.
        window.setLayout(1, 1)
    }

    override fun onResume() {
        super.onResume()
        if (app.isUnlocked) {
            finish()
        } else if (!started) {
            started = true
            if (getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true) {
                finish()
            } else {
                authenticating = true
                unlocker.start(allowEnrollment = false)
            }
        }
    }

    private val listener = object : BiometricVaultUnlock.Listener {
        override fun onUnavailable(availability: Int) = fail()
        override fun onNotProvisioned() = fail()
        override fun onKeyInvalidated() = fail()
        override fun onAuthenticationError(code: Int, message: CharSequence) {
            authenticating = false
            if (code == BiometricPrompt.ERROR_USER_CANCELED ||
                code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
            ) finish() else fail()
        }
        override fun onAuthenticationFailed() = Unit
        override fun onOpening() = Unit
        override fun onOpened(imported: Int) {
            authenticating = false
            // The IME will refresh only after verifying its active editor and epoch.
            finish()
        }
        override fun onFailure(message: String) = fail()
    }

    private fun fail() {
        authenticating = false
        if (!isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            Toast.makeText(applicationContext, R.string.secure_paste_unlock_failed, Toast.LENGTH_SHORT).show()
        }
        if (!isFinishing) finish()
    }

    override fun onStop() {
        super.onStop()
        if (!authenticating && !isFinishing) finish()
    }

    override fun onDestroy() {
        app.removeLockListener(lockListener)
        super.onDestroy()
    }
}
