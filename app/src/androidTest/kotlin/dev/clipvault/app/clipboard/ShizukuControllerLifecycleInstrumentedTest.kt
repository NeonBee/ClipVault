package dev.clipvault.app.clipboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Runs without Shizuku. Bridge callbacks arrive on Binder threads; these tests force the
 * interleavings with close() on the main thread and check that none captures after close.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuControllerLifecycleInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun callbackWhileStartedIsDeliveredOnlyThen() {
        val captured = Collections.synchronizedList(mutableListOf<String?>())
        val controller = ShizukuController(context, null) { text -> captured.add(text) }

        fromBinderThread { controller.deliverClipboardChange("before start") }
        instrumentation.runOnMainSync { controller.start() }
        fromBinderThread { controller.deliverClipboardChange("while started") }
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { controller.close() }
        fromBinderThread { controller.deliverClipboardChange("after close") }
        instrumentation.waitForIdleSync()

        assertEquals(listOf<String?>("while started"), captured.toList())
    }

    /**
     * Reviewer's interleaving: the Binder callback has entered and passed the old started check,
     * close() runs, then the actual delivery resumes. It must be dropped.
     */
    @Test fun inFlightCallbackRacingCloseIsDropped() {
        val captured = Collections.synchronizedList(mutableListOf<String?>())
        val controller = ShizukuController(context, null) { text -> captured.add(text) }
        instrumentation.runOnMainSync { controller.start() }

        instrumentation.runOnMainSync {
            // 1-2. The main looper is busy here, so the Binder callback enters while started is
            //      still true and its delivery waits just before the listener call.
            val entered = CountDownLatch(1)
            thread(name = "fake-binder") {
                controller.deliverClipboardChange("in flight")
                entered.countDown()
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // 3. close() runs before the pending delivery.
            controller.close()
        }
        // 4. The delivery resumes once the main looper is free.
        instrumentation.waitForIdleSync()

        // 5. Nothing is captured.
        assertEquals(0, captured.size)
    }

    private fun fromBinderThread(block: () -> Unit) {
        thread(name = "fake-binder") { block() }.join(5_000)
    }
}
