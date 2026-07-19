package dev.clipvault.app.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import dev.clipvault.app.nativecore.NativeClassifier;
import dev.clipvault.app.nativecore.TextAnalysis;

import org.junit.Test;

import java.util.Arrays;

public class CaptureRuleEngineTest {
    @Test
    public void domainRuleMatchesSubdomains() {
        CaptureRuleEngine engine = new CaptureRuleEngine();
        engine.replace(Arrays.asList(new CaptureRule(7, CaptureRule.Type.DOMAIN,
                "instagram.com", true, 0, 0)));
        TextAnalysis analysis = new TextAnalysis("https://m.instagram.com/p/1", NativeClassifier.LINK,
                false, "https://m.instagram.com/p/1", "m.instagram.com", 27);
        assertEquals(7, engine.firstMatch(analysis));
    }

    @Test
    public void disabledRuleDoesNotBlock() {
        CaptureRuleEngine engine = new CaptureRuleEngine();
        engine.replace(Arrays.asList(new CaptureRule(3, CaptureRule.Type.CONTAINS,
                "secret", false, 0, 0)));
        assertEquals(-1, engine.firstMatch(new TextAnalysis("secret", 0, false, "", "", 6)));
    }

    @Test
    public void re2ValidationRejectsInvalidExpression() {
        assertFalse(CaptureRuleEngine.isValid(CaptureRule.Type.REGEX, "([a-z]"));
        assertTrue(CaptureRuleEngine.isValid(CaptureRule.Type.REGEX, "^[a-z]+$"));
    }
}
