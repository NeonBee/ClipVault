package dev.clipvault.app.ui

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clipvault.app.ClipVaultApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Reviewer's ordering: the ViewModel reads diagnostics first (onVaultOpened -> refreshMetadata),
 * the capture service writes the bridge mode afterwards. Diagnostics must follow the later write.
 */
@RunWith(AndroidJUnit4::class)
class BridgeDiagnosticsViewModelInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app get() = context.applicationContext as ClipVaultApp
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before fun clearBridgeState() = writeBridgeState("", "")
    @After fun resetBridgeState() = writeBridgeState("", "")

    @Test fun bridgeModeWrittenAfterViewModelCreationIsShown() {
        val store = ViewModelStore()
        lateinit var viewModel: VaultViewModel
        instrumentation.runOnMainSync {
            viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory.getInstance(app))[VaultViewModel::class.java]
        }
        assertEquals("", viewModel.state.value.bridgeState)

        // The service's health callback runs after the ViewModel already read the preferences.
        writeBridgeState("READY_EVENT", "")
        assertEquals("READY_EVENT", viewModel.state.value.bridgeState)

        writeBridgeState("DEGRADED:BACKEND_NOT_SHELL", "DEGRADED:BACKEND_NOT_SHELL")
        assertEquals("DEGRADED:BACKEND_NOT_SHELL", viewModel.state.value.bridgeState)
        assertEquals("DEGRADED:BACKEND_NOT_SHELL", viewModel.state.value.lastCaptureError)

        // After onCleared the listener is gone: later writes no longer reach the cleared ViewModel.
        instrumentation.runOnMainSync { store.clear() }
        writeBridgeState("READY_POLL", "")
        assertEquals("DEGRADED:BACKEND_NOT_SHELL", viewModel.state.value.bridgeState)
    }

    /** commit() on the main thread so change listeners have run when this returns. */
    private fun writeBridgeState(state: String, error: String) {
        instrumentation.runOnMainSync {
            app.settings().edit()
                .putString(ClipVaultApp.PREF_BRIDGE_STATE, state)
                .putString(ClipVaultApp.PREF_LAST_CAPTURE_ERROR, error)
                .commit()
        }
        instrumentation.waitForIdleSync()
    }
}
