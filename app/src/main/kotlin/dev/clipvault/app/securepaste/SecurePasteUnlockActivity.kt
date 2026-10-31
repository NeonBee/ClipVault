package dev.clipvault.app.securepaste

import android.app.KeyguardManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.Lifecycle
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.R
import dev.clipvault.app.security.BiometricVaultUnlock
import dev.clipvault.app.security.VaultKeyManager

/**
 * Foreground-only strong biometric authentication host for the secondary IME.
 * Never holds the selected clip or the target InputConnection: after returning to the
 * editor the IME must reacquire and revalidate the connection before any insert.
 */
class SecurePasteUnlockActivity : AppCompatActivity() {
    private lateinit var app: ClipVaultApp
    private lateinit var unlocker: BiometricVaultUnlock
    private lateinit var message: TextView
    private var started = false
    private var authenticating = false
    private val lockListener = Runnable { if (!isFinishing) finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        app = application as ClipVaultApp
        app.addLockListener(lockListener)
        unlocker = BiometricVaultUnlock(this, app, VaultKeyManager(this), listener)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 48, 40, 40)
        }
        message = TextView(this).apply { setText(R.string.secure_paste_unlock_description) }
        container.addView(message)
        container.addView(Button(this).apply {
            setText(R.string.cancel)
            setOnClickListener { finish() }
        })
        setContentView(container)
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
                code == BiometricPrompt.ERROR_NEGATIVE_BUTTON) finish()
            else fail()
        }
        override fun onAuthenticationFailed() = Unit
        override fun onOpening() = Unit
        override fun onOpened(imported: Int) {
            authenticating = false
            // An unlock is never a license to paste into a stale editor connection.
            finish()
        }
        override fun onFailure(message: String) = fail()
    }

    private fun fail() {
        authenticating = false
        if (!isFinishing && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            message.setText(R.string.secure_paste_unlock_failed)
        } else if (!isFinishing) finish()
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
