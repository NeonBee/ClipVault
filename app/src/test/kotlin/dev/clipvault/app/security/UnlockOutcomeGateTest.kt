package dev.clipvault.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnlockOutcomeGateTest {
    private var epoch = 7L
    private var unlocked = false
    private val gate = UnlockOutcomeGate({ epoch }, { unlocked })
    private val listener = RecordingListener()
    private val mainQueue = ArrayDeque<() -> Unit>()

    /** What ClipVaultApp.lockVault does to the state the gate reads. */
    private fun lockVault() { epoch++; unlocked = false }

    /** What a successful openVault leaves behind before BiometricVaultUnlock posts its callback. */
    private fun openVaultSucceeded(unlockEpoch: Long, imported: Int) {
        unlocked = true
        mainQueue.addLast { gate.deliver(unlockEpoch, imported, listener) { "interrupted" } }
    }

    private fun drainMain() { while (mainQueue.isNotEmpty()) mainQueue.removeFirst()() }

    @Test fun openedIsDeliveredWhenNothingLockedInBetween() {
        val unlockEpoch = epoch
        openVaultSucceeded(unlockEpoch, imported = 3)
        drainMain()
        assertEquals(3, listener.opened)
        assertNull(listener.failure)
    }

    @Test fun lockBetweenOpenAndMainCallbackSuppressesOnOpened() {
        // Review PR #11: openVault succeeded, then a lock ran before the queued main-thread callback.
        val unlockEpoch = epoch
        openVaultSucceeded(unlockEpoch, imported = 3)
        lockVault()
        drainMain()
        assertNull(listener.opened)
        assertEquals("interrupted", listener.failure)
    }

    @Test fun zeroDelayAutoLockQueuedAheadOfTheCallbackAlsoSuppressesIt() {
        // openVault posts onVaultUnlocked() (0 ms auto-lock) before maintenance finishes and the success
        // callback is queued, so the lock runs first on the main thread.
        val unlockEpoch = epoch
        unlocked = true
        mainQueue.addLast { lockVault() }
        mainQueue.addLast { gate.deliver(unlockEpoch, 0, listener) { "interrupted" } }
        drainMain()
        assertNull(listener.opened)
        assertEquals("interrupted", listener.failure)
    }

    @Test fun lockThenReunlockInAnotherEpochStillRejectsTheOldCallback() {
        val unlockEpoch = epoch
        openVaultSucceeded(unlockEpoch, imported = 1)
        lockVault()
        unlocked = true                     // a newer unlock opened the vault again in a later epoch
        drainMain()
        assertNull(listener.opened)
        assertEquals("interrupted", listener.failure)
    }

    private class RecordingListener : BiometricVaultUnlock.Listener {
        var opened: Int? = null
        var failure: String? = null
        override fun onUnavailable(availability: Int) = Unit
        override fun onNotProvisioned() = Unit
        override fun onKeyInvalidated() = Unit
        override fun onAuthenticationError(code: Int, message: CharSequence) = Unit
        override fun onAuthenticationFailed() = Unit
        override fun onOpening() = Unit
        override fun onOpened(imported: Int) { opened = imported }
        override fun onFailure(message: String) { failure = message }
    }
}
