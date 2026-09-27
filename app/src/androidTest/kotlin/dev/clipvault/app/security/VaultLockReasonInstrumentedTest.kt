package dev.clipvault.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.ui.VaultViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Opens the same throwaway test vault as VaultNavigationComposeTest. Skipped when a real biometric
 * vault is provisioned, so it never touches a personal vault.
 */
@RunWith(AndroidJUnit4::class)
class VaultLockReasonInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val app = context.applicationContext as ClipVaultApp
    private var savedLog: String? = null

    @Before fun setUp() {
        assumeFalse("a real vault is provisioned on this device", VaultKeyManager(context).isProvisioned)
        savedLog = app.settings().getString(ClipVaultApp.PREF_LOCK_LOG, null)
        app.settings().edit().remove(ClipVaultApp.PREF_LOCK_LOG).commit()
        app.lockVault()
        drainIo()
    }

    @After fun tearDown() {
        app.lockVault()
        drainIo()
        val editor = app.settings().edit()
        if (savedLog == null) editor.remove(ClipVaultApp.PREF_LOCK_LOG) else editor.putString(ClipVaultApp.PREF_LOCK_LOG, savedLog)
        editor.remove(ClipVaultApp.PREF_VAULT_OPEN_MARKER).commit()
    }

    @Test fun lockFromOpenRecordsReasonAndClearsTheOpenMarker() {
        openTestVault()
        assertTrue("open marker set while unlocked", app.settings().contains(ClipVaultApp.PREF_VAULT_OPEN_MARKER))

        app.lockVault(VaultLockLog.Reason.USER)
        drainIo()
        val events = VaultLockLog.parse(app.settings().getString(ClipVaultApp.PREF_LOCK_LOG, ""))
        assertEquals(1, events.size)
        assertEquals(VaultLockLog.Reason.USER, events[0].reason)
        assertTrue(events[0].sinceUnlockMs >= 0)
        assertTrue(events[0].startedWindows >= 0)
        assertFalse("marker cleared by a recorded lock", app.settings().contains(ClipVaultApp.PREF_VAULT_OPEN_MARKER))
    }

    @Test fun repeatedLocksWhileAlreadyLockedAreNotRecorded() {
        // onResume/onStart keyguard checks and screen-off arrive while locked; they must not flood the log.
        app.lockVault(VaultLockLog.Reason.SCREEN_OFF)
        app.lockVault(VaultLockLog.Reason.DEVICE_LOCKED_MAIN)
        drainIo()
        assertEquals(0, VaultLockLog.parse(app.settings().getString(ClipVaultApp.PREF_LOCK_LOG, "")).size)
    }

    @Test fun lockReasonSurvivesTheLockResetAndReopenInDiagnostics() {
        // Review PR #12: lockVault(reason) writes the log, then the lock listener resets VaultUiState
        // (onVaultLocked), then the user re-authenticates (onVaultOpened) and opens Diagnostics.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        lateinit var viewModel: VaultViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[VaultViewModel::class.java]
        }
        val lockListener = Runnable { viewModel.onVaultLocked() }   // as MainActivity registers it
        app.addLockListener(lockListener)
        try {
            openTestVault()
            instrumentation.runOnMainSync { viewModel.onVaultOpened(0) }

            instrumentation.runOnMainSync { app.lockVault(VaultLockLog.Reason.DEVICE_LOCKED_MAIN) }
            drainIo()
            assertFalse(viewModel.state.value.unlocked)
            assertEquals(VaultLockLog.Reason.DEVICE_LOCKED_MAIN, viewModel.state.value.lockEvents.firstOrNull()?.reason)

            openTestVault()
            instrumentation.runOnMainSync { viewModel.onVaultOpened(0) }
            assertTrue(viewModel.state.value.unlocked)
            assertEquals(VaultLockLog.Reason.DEVICE_LOCKED_MAIN, viewModel.state.value.lockEvents.firstOrNull()?.reason)
        } finally {
            app.removeLockListener(lockListener)
            instrumentation.runOnMainSync { store.clear() }
        }
    }

    private fun openTestVault() {
        app.io().submit<Int> { app.openVault(ByteArray(32) { (it + 91).toByte() }) }.get(10, TimeUnit.SECONDS)
        assertTrue(app.isUnlocked)
    }

    private fun drainIo() {
        app.io().submit { }.get(10, TimeUnit.SECONDS)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }
}
