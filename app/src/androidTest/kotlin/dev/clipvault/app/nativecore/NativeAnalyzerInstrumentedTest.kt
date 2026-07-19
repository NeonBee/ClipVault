package dev.clipvault.app.nativecore

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeAnalyzerInstrumentedTest {
    @Test fun nativeCorpusClassifiesTypesLanguagesAndCanonicalDomains() {
        val cases = listOf(
            "https://www.instagram.com/p/abc" to NativeClassifier.INSTAGRAM,
            "https://youtu.be/abc" to NativeClassifier.YOUTUBE,
            "https://t.me/clipvault" to NativeClassifier.TELEGRAM,
            "https://www.tiktok.com/@clip/video/1" to NativeClassifier.TIKTOK,
            "https://x.com/clipvault/status/1" to NativeClassifier.X_TWITTER,
            "https://github.com/example/repository" to NativeClassifier.GITHUB,
            "mail@example.com" to NativeClassifier.EMAIL,
            "+98 912 123 4567" to NativeClassifier.PHONE,
            "2026-07-19" to NativeClassifier.DATE,
            "{\"offline\":true}" to NativeClassifier.JSON,
            "public static void main() {}" to NativeClassifier.CODE,
            "سلام" to NativeClassifier.PERSIAN,
            "hello" to NativeClassifier.ENGLISH,
            "سلام ClipVault" to NativeClassifier.MIXED_LANGUAGE,
            "a".repeat(280) to NativeClassifier.LONG_TEXT,
        )
        cases.forEach { (text, expected) ->
            assertTrue("Expected flag $expected for $text", NativeClassifier.analyze(text).has(expected))
        }
        assertEquals("instagram.com", NativeClassifier.analyze(cases.first().first).domain)
        assertFalse(NativeClassifier.analyze("https://evilinstagram.com/p/abc").has(NativeClassifier.INSTAGRAM))
    }

    @Test fun nativeCorpusDetectsSensitiveMaterial() {
        listOf(
            "OTP 123456",
            "api_key = secret-value",
            "رمز عبور: نمونه",
            "4111 1111 1111 1111",
        ).forEach { assertTrue("Expected sensitive: $it", NativeClassifier.analyze(it).sensitive) }
    }

    @Test fun nativeAndFallbackAgreeOnSupportedCorpus() {
        val corpus = listOf(
            "  https://youtu.be/video.  ",
            "سلام ClipVault",
            "person@example.com",
            "2026/07/19",
            "const value = {\"enabled\": true}",
            "کد تایید ۱۲۳۴۵۶",
            "4111111111111111",
        )
        corpus.forEach { input ->
            val native = NativeClassifier.analyze(input)
            val fallback = NativeClassifier.fallbackAnalyze(input)
            assertEquals("flags for $input", fallback.flags, native.flags)
            assertEquals("sensitivity for $input", fallback.sensitive, native.sensitive)
            assertEquals("domain for $input", fallback.domain, native.domain)
        }
    }

    @Test fun malformedUtf16DoesNotCrashAnalyzer() {
        val malformed = "prefix\uD800suffix\uDC00"
        val result = NativeClassifier.analyze(malformed)
        assertTrue(result.normalized.isNotEmpty())
        assertTrue(result.characterCount > 0)
    }
}
