package dev.clipvault.app.nativecore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class NativeClassifierTest {
    @Test
    public void browseCandidatesClassifyKoreanSshCredentialFinanceAndIdentityWithoutChangingCaptureSkip() {
        TextAnalysis korean = NativeClassifier.fallbackAnalyze("서버 접속 방법을 정리한 문서");
        assertTrue(korean.has(NativeClassifier.KOREAN));
        assertFalse(korean.sensitive);

        TextAnalysis ssh = NativeClassifier.fallbackAnalyze("ssh user@host.example");
        assertTrue(ssh.has(NativeClassifier.SSH_COMMAND));
        assertFalse(ssh.has(NativeClassifier.CREDENTIAL));
        assertFalse(ssh.sensitive);

        TextAnalysis key = NativeClassifier.fallbackAnalyze("-----BEGIN OPENSSH PRIVATE KEY-----");
        assertTrue(key.has(NativeClassifier.CREDENTIAL));
        assertTrue(key.sensitive); // Pre-existing 'private key' legacy rule still skips auto-capture.

        TextAnalysis finance = NativeClassifier.fallbackAnalyze("입금계좌 안내");
        assertTrue(finance.has(NativeClassifier.FINANCE));
        TextAnalysis identity = NativeClassifier.fallbackAnalyze("여권번호 확인");
        assertTrue(identity.has(NativeClassifier.IDENTITY));
    }

    @Test
    public void tokenizedUrlIsMultiCategoryButNoAutomaticCapturePolicyDowngrade() {
        TextAnalysis result = NativeClassifier.fallbackAnalyze(
                "https://example.test/reset?token=TESTONLY123456");
        assertTrue(result.has(NativeClassifier.CREDENTIAL));
        assertTrue(result.has(NativeClassifier.LINK));
        assertFalse(result.sensitive); // Extra browsing labels do not modify legacy sensitive detection.
    }

    @Test
    public void ordinaryUrlAndRandomPlainSentenceRemainOutsideCredentialFolder() {
        assertFalse(NativeClassifier.fallbackAnalyze("https://example.test/docs").has(NativeClassifier.CREDENTIAL));
        assertFalse(NativeClassifier.fallbackAnalyze("공개 자료를 나중에 읽기").has(NativeClassifier.CREDENTIAL));
    }

    @Test
    public void fallbackFindsYoutubeAndDomain() {
        TextAnalysis result = NativeClassifier.fallbackAnalyze("  https://youtu.be/abc  ");
        assertTrue(result.has(NativeClassifier.YOUTUBE));
        assertEquals("youtu.be", result.domain);
        assertEquals("https://youtu.be/abc", result.normalized);
    }

    @Test
    public void fallbackMarksMixedPersianEnglish() {
        TextAnalysis result = NativeClassifier.fallbackAnalyze("سلام ClipVault");
        assertTrue(result.has(NativeClassifier.PERSIAN));
        assertTrue(result.has(NativeClassifier.ENGLISH));
        assertTrue(result.has(NativeClassifier.MIXED_LANGUAGE));
    }

    @Test
    public void fallbackDetectsOtp() {
        assertTrue(NativeClassifier.fallbackAnalyze("کد تایید 123456").sensitive);
    }

    @Test
    public void fallbackRejectsLookalikeDomainAndDetectsPaymentNumber() {
        assertFalse(NativeClassifier.fallbackAnalyze("https://evilinstagram.com/p/1")
                .has(NativeClassifier.INSTAGRAM));
        assertTrue(NativeClassifier.fallbackAnalyze("4111 1111 1111 1111").sensitive);
    }
}
