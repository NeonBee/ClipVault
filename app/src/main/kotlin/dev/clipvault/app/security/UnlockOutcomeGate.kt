package dev.clipvault.app.security

/**
 * Decides, on the main thread, whether a finished unlock may still be reported as opened.
 *
 * `ClipVaultApp.openVault` publishes the repository and then keeps running maintenance and the pending
 * import on the IO executor; the success callback is queued on the main thread after that. A lock that
 * lands in between (screen off, notification lock, a 0 ms auto-lock, explicit lock) bumps the lock
 * epoch and closes the vault, so a late `onOpened` must not flip the UI back to unlocked. The check has
 * to run inside the main-thread callback, not before posting it, to also catch locks queued ahead of it.
 */
class UnlockOutcomeGate(
    private val currentEpoch: () -> Long,
    private val isUnlocked: () -> Boolean,
) {
    /** Main thread only. [unlockEpoch] is the lock epoch captured before the biometric prompt. */
    fun deliver(unlockEpoch: Long, imported: Int, listener: BiometricVaultUnlock.Listener, interruptedMessage: () -> String) {
        if (currentEpoch() == unlockEpoch && isUnlocked()) listener.onOpened(imported)
        else listener.onFailure(interruptedMessage())
    }
}
