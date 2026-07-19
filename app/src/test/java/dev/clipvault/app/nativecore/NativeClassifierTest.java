package dev.clipvault.app.nativecore;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class NativeClassifierTest {
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
