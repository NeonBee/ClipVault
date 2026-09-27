package dev.clipvault.app.ui

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.security.VaultLockLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LockDiagnosticsViewModelInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app get() = context.applicationContext as ClipVaultApp
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var saved: String? = null

    @Before fun saveLog() { saved = app.settings().getString(ClipVaultApp.PREF_LOCK_LOG, null) }
    @After fun restoreLog() = writeLog(saved)

    @Test fun lockEventsWrittenLaterReachDiagnostics() {
        writeLog(null)
        val store = ViewModelStore()
        lateinit var viewModel: VaultViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[VaultViewModel::class.java]
        }
        assertEquals(0, viewModel.state.value.lockEvents.size)

        val log = VaultLockLog.append(null, VaultLockLog.Event(VaultLockLog.Reason.DEVICE_LOCKED_MAIN,
            1_000L, 5_000L, 1, 1, 0, 1, 1))
        writeLog(log)
        val events = viewModel.state.value.lockEvents
        assertEquals(1, events.size)
        assertEquals(VaultLockLog.Reason.DEVICE_LOCKED_MAIN, events[0].reason)
        instrumentation.runOnMainSync { store.clear() }
    }

    private fun writeLog(value: String?) {
        instrumentation.runOnMainSync {
            val editor = app.settings().edit()
            if (value == null) editor.remove(ClipVaultApp.PREF_LOCK_LOG) else editor.putString(ClipVaultApp.PREF_LOCK_LOG, value)
            editor.commit()
        }
        instrumentation.waitForIdleSync()
    }
}
