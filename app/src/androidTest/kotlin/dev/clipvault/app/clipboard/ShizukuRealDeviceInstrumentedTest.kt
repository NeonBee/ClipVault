package dev.clipvault.app.clipboard

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs on an explicitly prepared physical device and skips cleanly when Shizuku is absent in CI.
 * This is the entry point for the PR-04 device compatibility matrix.
 */
@RunWith(AndroidJUnit4::class)
class ShizukuRealDeviceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun protocolV3ShellBridgeProbesTheHiddenClipboardApi() {
        assumeTrue("Shizuku server is not running", ShizukuController.isBinderRunning())
        assumeTrue("ClipVault has no Shizuku permission", ShizukuController.hasPermission())
        assumeTrue("Shizuku runs as root/Sui; see the refusal test", ShizukuController.isShellBackend())

        val health = awaitSettledHealth()
        assertTrue("Bridge did not become ready: $health", health.isReady)
        // Exercises the privileged transaction; an empty clipboard may return null.
    }

    @Test fun rootBackendIsRefusedBeforeBinding() {
        assumeTrue("Shizuku server is not running", ShizukuController.isBinderRunning())
        assumeTrue("ClipVault has no Shizuku permission", ShizukuController.hasPermission())
        assumeFalse("Shizuku uses the shell backend", ShizukuController.isShellBackend())

        val health = awaitSettledHealth()
        assertEquals(BridgeHealth.degraded(ClipboardBridgeProtocol.BACKEND_NOT_SHELL), health)
    }

    private fun awaitSettledHealth(): BridgeHealth {
        val settled = CountDownLatch(1)
        var last = BridgeHealth.of(BridgeHealth.State.NO_SHIZUKU)
        val controller = ShizukuController(context, { health ->
            last = health
            if (health.isReady || health.state == BridgeHealth.State.DEGRADED) settled.countDown()
        })
        instrumentation.runOnMainSync { controller.start() }
        try {
            settled.await(15, TimeUnit.SECONDS)
            if (last.isReady) controller.readText()
            return last
        } finally {
            instrumentation.runOnMainSync { controller.close() }
        }
    }
}
