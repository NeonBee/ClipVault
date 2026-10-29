package dev.clipvault.app.securecopy

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureCopyIntentInstrumentedTest {
    private fun request(text: CharSequence): Intent =
        Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }

    @Test fun acceptsExactSelectedTextIncludingKoreanAndLineBreaks() {
        val content = "  한국어\ncode = 123  "
        assertEquals(content, SecureCopyRequest.selectedText(request(content)))
    }

    @Test fun rejectsNonProcessTextAndNonPlainMimeWithoutClipboardFallback() {
        val text = "Do not capture"
        assertNull(SecureCopyRequest.selectedText(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        }))
        assertNull(SecureCopyRequest.selectedText(request(text).apply { type = "text/html" }))
    }

    @Test fun rejectsBlankMissingAndOversizedPayload() {
        assertNull(SecureCopyRequest.selectedText(request("  \n  ")))
        assertNull(SecureCopyRequest.selectedText(Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")))
        assertNull(SecureCopyRequest.selectedText(request("x".repeat(SecureCopyRequest.MAX_SELECTED_CHARS + 1))))
    }

    @Test fun processTextManifestRoutingTargetsOnlyTheSecureEntry() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage(context.packageName)
        }
        val targets = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.name }
        assertTrue("Missing Secure Copy text-selection entry", targets.contains(SecureCopyActivity::class.java.name))
        assertFalse("Old MainActivity capture/staging entry must not receive PROCESS_TEXT",
            targets.contains("dev.clipvault.app.MainActivity"))
        assertEquals("Only one secure selection ingress should be exposed", 1, targets.size)
    }
}
