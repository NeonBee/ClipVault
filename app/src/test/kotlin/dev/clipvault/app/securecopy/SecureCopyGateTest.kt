package dev.clipvault.app.securecopy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureCopyGateTest {
    @Test fun lockedVaultNeverCommits() {
        assertFalse(SecureCopyGate.canCommit(false, 3L, 3L, true))
    }

    @Test fun lockEpochChangeInvalidatesPendingIngress() {
        assertFalse(SecureCopyGate.canCommit(true, 3L, 4L, true))
    }

    @Test fun cancelledOrBackgroundedRequestNeverCommits() {
        assertFalse(SecureCopyGate.canCommit(true, 3L, 3L, false))
    }

    @Test fun unlockedVaultWithMatchingEpochMayCommit() {
        assertTrue(SecureCopyGate.canCommit(true, 3L, 3L, true))
    }
}
