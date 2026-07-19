package dev.clipvault.app.data;

import androidx.annotation.NonNull;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;

import dev.clipvault.app.nativecore.TextAnalysis;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Evaluates block-only capture rules without backtracking regular expressions. */
public final class CaptureRuleEngine {
    private volatile List<CaptureRule> rules = Collections.emptyList();

    public void replace(@NonNull List<CaptureRule> next) {
        rules = Collections.unmodifiableList(new java.util.ArrayList<>(next));
    }

    @NonNull
    public List<CaptureRule> snapshot() {
        return rules;
    }

    public long firstMatch(@NonNull TextAnalysis analysis) {
        String lower = analysis.normalized.toLowerCase(Locale.ROOT);
        String domain = analysis.domain.toLowerCase(Locale.ROOT);
        for (CaptureRule rule : rules) {
            if (!rule.enabled) continue;
            if (matches(rule, lower, domain, analysis.characterCount)) return rule.id;
        }
        return -1L;
    }

    public static boolean isValid(@NonNull CaptureRule.Type type, @NonNull String pattern) {
        String value = pattern.trim();
        if (value.isEmpty() || value.length() > 512) return false;
        if (type == CaptureRule.Type.MIN_LENGTH || type == CaptureRule.Type.MAX_LENGTH) {
            try {
                int number = Integer.parseInt(value);
                return number >= 1 && number <= 1_000_000;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        if (type == CaptureRule.Type.REGEX) {
            try {
                Pattern.compile(value, Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException error) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(CaptureRule rule, String text, String domain, int length) {
        String pattern = rule.pattern.trim().toLowerCase(Locale.ROOT);
        switch (rule.type) {
            case DOMAIN:
                String clean = pattern.startsWith("*.") ? pattern.substring(2) : pattern;
                return !domain.isEmpty() && (domain.equals(clean) || domain.endsWith("." + clean));
            case CONTAINS:
                return text.contains(pattern);
            case PREFIX:
                return text.startsWith(pattern);
            case REGEX:
                try {
                    return Pattern.compile(rule.pattern, Pattern.CASE_INSENSITIVE).matcher(text).find();
                } catch (PatternSyntaxException ignored) {
                    return false;
                }
            case MIN_LENGTH:
                return length < Integer.parseInt(pattern);
            case MAX_LENGTH:
                return length > Integer.parseInt(pattern);
            default:
                return false;
        }
    }
}
