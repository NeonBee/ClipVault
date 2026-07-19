package dev.clipvault.app.clipboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs on an explicitly prepared physical device and skips cleanly when Shizuku is absent in CI. */
@RunWith(AndroidJUnit4::class)
class ShizukuRealDeviceInstrumentedTest {
    @Test fun protocolV2UserServiceBindsThroughLiveShizuku() {
        assumeTrue("Shizuku server is not running", ShizukuController.isBinderRunning())
        assumeTrue("ClipVault has no Shizuku permission", ShizukuController.hasPermission())

        val context = ApplicationProvider.getApplicationContext<Context>()
        val ready = CountDownLatch(1)
        val controller = ShizukuController(context, { connected -> if (connected) ready.countDown() })
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        instrumentation.runOnMainSync { controller.start() }
        try {
            assertTrue("Protocol v2 clipboard UserService did not bind", ready.await(15, TimeUnit.SECONDS))
            assertTrue(controller.isReady)
            controller.readText() // Exercises the privileged transaction; an empty clipboard may return null.
        } finally {
            instrumentation.runOnMainSync { controller.close() }
        }
    }
}
