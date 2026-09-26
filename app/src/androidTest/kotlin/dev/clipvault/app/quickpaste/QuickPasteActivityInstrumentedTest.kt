package dev.clipvault.app.quickpaste

import android.content.ClipDescription
import android.os.Build
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.clipboard.SensitiveClipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * Never opens or creates the vault database, so running it on a personal device leaves user data
 * alone. It only locks the vault first (other suites in this process may have opened a test vault).
 */
@RunWith(AndroidJUnit4::class)
class QuickPasteActivityInstrumentedTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as ClipVaultApp

    @Before fun lockVault() {
        app.lockVault()
        app.io().submit { }.get(10, TimeUnit.SECONDS)
    }

    @Test fun windowIsSecureAndLockedVaultShowsNoResults() {
        ActivityScenario.launch(QuickPasteActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val flags = activity.window.attributes.flags
                assertNotEquals(0, flags and WindowManager.LayoutParams.FLAG_SECURE)
                val state = activity.viewModels().state.value
                assertNotEquals(QuickPasteGate.READY, state.gate)
                assertTrue(state.results.isEmpty())
                assertEquals("", state.query)
            }
        }
    }

    @Test fun leavingTheWindowFinishesIt() {
        val scenario = ActivityScenario.launch(QuickPasteActivity::class.java)
        // Wait until any automatic unlock attempt has settled (no biometrics on CI emulators).
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        var authenticating = false
        scenario.onActivity { authenticating = it.viewModels().state.value.gate == QuickPasteGate.UNLOCKING }
        assumeFalse("a real biometric prompt is up", authenticating)
        // The window finishes itself inside onStop, so moveToState may report the target it never reached.
        runCatching { scenario.moveToState(Lifecycle.State.CREATED) }
        val deadline = System.currentTimeMillis() + 5_000L
        while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            Thread.sleep(50)
        }
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        scenario.close()
    }

    @Test fun copyBackClipIsMarkedSensitive() {
        val clip = SensitiveClipboard.clip("secret")
        val extras = clip.description.extras
        val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else SensitiveClipboard.EXTRA_IS_SENSITIVE
        assertTrue(extras.getBoolean(key))
        assertEquals("secret", clip.getItemAt(0).text.toString())
    }

    private fun QuickPasteActivity.viewModels(): QuickPasteViewModel =
        androidx.lifecycle.ViewModelProvider(this)[QuickPasteViewModel::class.java]
}
