package dev.clipvault.app.security

import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.R
import java.security.GeneralSecurityException
import javax.crypto.Cipher

/**
 * Opens the vault by unwrapping the database key with a BiometricPrompt-authenticated CryptoObject.
 * MainActivity and QuickPasteActivity share this so both cross the same cryptographic boundary;
 * there is no plaintext shortcut. QuickPaste passes allowEnrollment = false: first-time setup
 * stays in the main app.
 */
class BiometricVaultUnlock(
    private val activity: FragmentActivity,
    private val app: ClipVaultApp,
    private val keyManager: VaultKeyManager,
    private val listener: Listener,
) {
    interface Listener {
        /** Strong biometrics are unusable; [availability] is the BiometricManager result code. */
        fun onUnavailable(availability: Int)
        /** The vault key is not provisioned yet and the caller does not allow enrollment. */
        fun onNotProvisioned()
        /** The wrapping key was invalidated, e.g. by a biometric enrollment change. */
        fun onKeyInvalidated()
        fun onAuthenticationError(code: Int, message: CharSequence)
        fun onAuthenticationFailed()
        /** Authentication succeeded; the database is being opened on the IO executor. */
        fun onOpening()
        fun onOpened(imported: Int)
        fun onFailure(message: String)
    }

    private val outcomeGate = UnlockOutcomeGate(app::lockEpoch, app::isUnlocked)

    fun start(allowEnrollment: Boolean) {
        val availability = BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            listener.onUnavailable(availability)
            return
        }
        val enrolling = !keyManager.isProvisioned
        if (enrolling && !allowEnrollment) {
            listener.onNotProvisioned()
            return
        }
        // Screen-off / keyguard / explicit lock after this point invalidates the attempt (see openVault).
        val epoch = app.lockEpoch()
        try {
            val cipher = if (enrolling) keyManager.createEnrollmentCipher() else keyManager.createUnlockCipher()
            val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(code: Int, message: CharSequence) =
                        listener.onAuthenticationError(code, message)
                    override fun onAuthenticationFailed() = listener.onAuthenticationFailed()
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        val authenticated = result.cryptoObject?.cipher
                        if (authenticated == null) listener.onFailure(activity.getString(R.string.crypto_object_missing))
                        else openVault(authenticated, enrolling, epoch)
                    }
                })
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.unlock_prompt_title))
                .setSubtitle(activity.getString(R.string.unlock_prompt_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(activity.getString(R.string.cancel))
                .setConfirmationRequired(false)
                .build()
            prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
        } catch (_: KeyPermanentlyInvalidatedException) {
            listener.onKeyInvalidated()
        } catch (error: GeneralSecurityException) {
            listener.onFailure(error.message ?: activity.getString(R.string.unlock_failed))
        }
    }

    private fun openVault(cipher: Cipher, enrollment: Boolean, epoch: Long) {
        listener.onOpening()
        app.io().execute {
            var databaseKey: ByteArray? = null
            try {
                databaseKey = if (enrollment) keyManager.finishEnrollment(cipher) else keyManager.finishUnlock(cipher)
                val imported = app.openVault(databaseKey, epoch)
                databaseKey = null
                activity.runOnUiThread {
                    outcomeGate.deliver(epoch, imported, listener) { activity.getString(R.string.unlock_interrupted) }
                }
            } catch (_: ClipVaultApp.UnlockInterruptedException) {
                databaseKey?.fill(0)
                activity.runOnUiThread { listener.onFailure(activity.getString(R.string.unlock_interrupted)) }
            } catch (error: GeneralSecurityException) {
                databaseKey?.fill(0)
                activity.runOnUiThread { listener.onFailure(error.message ?: activity.getString(R.string.unlock_failed)) }
            } catch (error: RuntimeException) {
                databaseKey?.fill(0)
                activity.runOnUiThread { listener.onFailure(error.message ?: activity.getString(R.string.unlock_failed)) }
            }
        }
    }
}
