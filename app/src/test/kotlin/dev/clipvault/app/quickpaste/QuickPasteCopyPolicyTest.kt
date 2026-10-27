package dev.clipvault.app.quickpaste

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Copying must remain opt-in persistent and cannot imply an always-on-top overlay. */
class QuickPasteCopyPolicyTest {
    @Test fun defaultBehaviorClosesAfterCopy() {
        assertTrue(QuickPasteCopyPolicy.shouldCloseAfterCopy(keepOpenAfterCopy = false))
    }

    @Test fun optedInBehaviorKeepsWindowOpenAfterCopy() {
        assertFalse(QuickPasteCopyPolicy.shouldCloseAfterCopy(keepOpenAfterCopy = true))
    }
}
