package dev.clipvault.app.clipboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

/** Runs without Shizuku: checks that bridge callbacks cannot capture outside start()..close(). */
@RunWith(AndroidJUnit4::class)
class ShizukuControllerLifecycleInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun lateBridgeCallbackAfterCloseIsNotCaptured() {
        val captured = Collections.synchronizedList(mutableListOf<String?>())
        val controller = ShizukuController(context, null) { text -> captured.add(text) }

        controller.deliverClipboardChange("before start")
        instrumentation.runOnMainSync { controller.start() }
        controller.deliverClipboardChange("while started")
        instrumentation.runOnMainSync { controller.close() }
        // Simulates a binder callback that was already in flight when close() unregistered.
        controller.deliverClipboardChange("late after close")

        assertEquals(listOf<String?>("while started"), captured.toList())
    }
}
