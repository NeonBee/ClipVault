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

    @Test fun browseFlagsAreIdenticalAfterNativeAndJavaFallbackAnalysis() {
        val corpus = listOf(
            "한국어 개인 메모",
            "ssh user@host.example",
            "-----BEGIN OPENSSH PRIVATE KEY-----",
            "https://example.test/path?token=FAKE123456",
            "입금계좌를 확인하세요",
            "주민등록번호 관련 안내",
            "https://example.test/ordinary",
            "email@example.test",
        )
        corpus.forEach { sample ->
            val native = NativeClassifier.analyze(sample)
            val java = NativeClassifier.fallbackAnalyze(sample)
            assertEquals("flags for synthetic input", java.flags, native.flags)
            assertEquals("legacy sensitivity unchanged", java.sensitive, native.sensitive)
        }
    }

    @Test fun browseOnlyTokenLabelsNeverSilentlyBecomeSensitiveCaptureRejections() {
        val candidate = NativeClassifier.analyze("https://example.test/?token=FAKE123456")
        assertTrue(candidate.has(NativeClassifier.CREDENTIAL))
        assertTrue(candidate.has(NativeClassifier.LINK))
        assertFalse(candidate.sensitive)
        val ssh = NativeClassifier.analyze("ssh user@host.example")
        assertTrue(ssh.has(NativeClassifier.SSH_COMMAND))
    }

    @Test fun malformedUtf16DoesNotCrashAnalyzer() {
        val malformed = "prefix\uD800suffix\uDC00"
        val result = NativeClassifier.analyze(malformed)
        assertTrue(result.normalized.isNotEmpty())
        assertTrue(result.characterCount > 0)
    }
}
