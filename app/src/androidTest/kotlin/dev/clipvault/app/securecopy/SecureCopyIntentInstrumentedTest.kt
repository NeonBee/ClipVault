package dev.clipvault.app.securecopy

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.clipvault.app.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureCopyIntentInstrumentedTest {
    private fun selection(text: CharSequence): Intent =
        Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }

    private fun share(text: CharSequence): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }

    @Test fun preservesExplicitKoreanAndLineBreaksAtBothEntryPoints() {
        val content = "  한국어\ncode = 123  "
        assertEquals(content, SecureCopyRequest.incomingText(selection(content)))
        assertEquals(content, SecureCopyRequest.incomingText(share(content)))
    }

    @Test fun rejectsWrongMimeActionOrSwappedExtras() {
        val text = "Do not capture"
        assertNull(SecureCopyRequest.incomingText(selection(text).apply { type = "text/html" }))
        assertNull(SecureCopyRequest.incomingText(share(text).apply { type = "text/html" }))
        assertNull(SecureCopyRequest.incomingText(Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }))
        assertNull(SecureCopyRequest.incomingText(Intent(Intent.ACTION_VIEW).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }))
        assertNull(SecureCopyRequest.incomingText(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
        }))
        assertNull(SecureCopyRequest.incomingText(Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }))
    }

    @Test fun rejectsBlankMissingStreamAndOversizedPayloadWithoutFallback() {
        assertNull(SecureCopyRequest.incomingText(selection("  \n  ")))
        assertNull(SecureCopyRequest.incomingText(share(" \t ")))
        assertNull(SecureCopyRequest.incomingText(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse("content://other.provider/private"))
        }))
        // A text/plain intent can carry both readable text and an attachment/URI.
        // Secure Copy must reject ambiguous mixed payloads rather than silently
        // saving only the text and implying that the attachment was captured.
        assertNull(SecureCopyRequest.incomingText(share("visible text").apply {
            putExtra(Intent.EXTRA_STREAM, android.net.Uri.parse("content://other.provider/secret"))
        }))
        assertNull(SecureCopyRequest.incomingText(share("visible text").apply {
            setDataAndType(android.net.Uri.parse("content://other.provider/secret"), "text/plain")
        }))
        assertNull(SecureCopyRequest.incomingText(selection("visible text").apply {
            setDataAndType(android.net.Uri.parse("content://other.provider/secret"), "text/plain")
        }))
        assertNull(SecureCopyRequest.incomingText(share("visible text").apply {
            clipData = android.content.ClipData.newRawUri(
                "attachment", android.net.Uri.parse("content://other.provider/secret")
            )
        }))
        // Pure text ClipData is common on share intents; keep it compatible.
        assertEquals("visible text", SecureCopyRequest.incomingText(share("visible text").apply {
            clipData = android.content.ClipData.newPlainText("text", "visible text")
        }))
        assertNull(SecureCopyRequest.incomingText(Intent(Intent.ACTION_PROCESS_TEXT).apply { type = "text/plain" }))
        val huge = "x".repeat(SecureCopyRequest.MAX_SELECTED_CHARS + 1)
        assertNull(SecureCopyRequest.incomingText(selection(huge)))
        assertNull(SecureCopyRequest.incomingText(share(huge)))
    }

    @Test fun processTextManifestRoutesOnlyToSecureEntry() {
        val targets = targetsFor(Intent.ACTION_PROCESS_TEXT)
        assertEquals(listOf(SecureCopyActivity::class.java.name), targets.sorted())
        assertFalse(targets.contains(MainActivity::class.java.name))
    }

    @Test fun shareSheetOffersExplicitSecureTargetAlongsideExistingNormalShare() {
        val targets = targetsFor(Intent.ACTION_SEND)
        assertTrue("Secure share target missing", targets.contains(SecureCopyActivity::class.java.name))
        assertTrue("Legacy main-app share target must remain", targets.contains(MainActivity::class.java.name))
        assertEquals("Only the two intentional text/plain share targets", 2, targets.size)
    }

    @Test fun consumeSelectionClearsTransientIntentPayload() {
        // Deterministic ingress test; launching ActivityScenario on a busy CI emulator can
        // stall before onCreate, which is not evidence of an ingress security violation.
        val source = selection("private source text").apply {
            clipData = android.content.ClipData.newPlainText("text", "private source text")
        }
        assertEquals("private source text", SecureCopyRequest.consumeAndClear(source))
        assertFalse(source.hasExtra(Intent.EXTRA_PROCESS_TEXT))
        assertFalse(source.hasExtra(Intent.EXTRA_TEXT))
        assertNull(source.clipData)
        assertNull(source.data)
        assertNull(SecureCopyRequest.consumeAndClear(source))
    }

    private fun targetsFor(action: String): List<String> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val intent = Intent(action).apply {
            type = "text/plain"
            addCategory(Intent.CATEGORY_DEFAULT)
            setPackage(context.packageName)
        }
        return context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.name }
    }
}
