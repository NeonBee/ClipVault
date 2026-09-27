package dev.clipvault.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clipvault.app.ClipVaultApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * Review PR #11: a lock (screen off, keyguard, explicit) during an unlock must stop the late database
 * open from reopening the vault. The epoch check runs before any database file is touched, so this
 * test never opens or modifies a vault.
 */
@RunWith(AndroidJUnit4::class)
class VaultUnlockEpochInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ClipVaultApp

    @Test fun lockDuringUnlockRejectsTheLateOpenAndWipesTheKey() {
        val epochAtPrompt = app.lockEpoch()
        app.lockVault()                               // e.g. screen off while the prompt / open is in flight
        assertTrue(app.lockEpoch() != epochAtPrompt)

        val key = ByteArray(32) { 0x5A }
        val failure = try {
            app.io().submit<Int> { app.openVault(key, epochAtPrompt) }.get(10, TimeUnit.SECONDS)
            null
        } catch (error: ExecutionException) {
            error.cause
        }
        assertTrue("expected UnlockInterruptedException, got $failure",
            failure is ClipVaultApp.UnlockInterruptedException)
        assertFalse(app.isUnlocked)
        assertTrue("database key must be wiped", key.all { it == 0.toByte() })
    }
}
