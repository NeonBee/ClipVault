package dev.clipvault.app.nativecore;

import androidx.annotation.NonNull;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NativeClassifier {
    public static final int LINK = 1;
    public static final int INSTAGRAM = 1 << 1;
    public static final int YOUTUBE = 1 << 2;
    public static final int PERSIAN = 1 << 3;
    public static final int ENGLISH = 1 << 4;
    public static final int LONG_TEXT = 1 << 5;
    public static final int DATE = 1 << 6;
    public static final int OTHER = 1 << 7;
    public static final int TELEGRAM = 1 << 8;
    public static final int TIKTOK = 1 << 9;
    public static final int X_TWITTER = 1 << 10;
    public static final int GITHUB = 1 << 11;
    public static final int EMAIL = 1 << 12;
    public static final int PHONE = 1 << 13;
    public static final int CODE = 1 << 14;
    public static final int JSON = 1 << 15;
    public static final int MIXED_LANGUAGE = 1 << 16;
    public static final int SENSITIVE = 1 << 17;

    private static final Pattern URL = Pattern.compile("(?i)(https?://|www\\.)[^\\s<>]+", Pattern.UNICODE_CASE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)[\\p{L}0-9._%+-]+@[\\p{L}0-9.-]+\\.[A-Z]{2,}");
    private static final boolean NATIVE_AVAILABLE;

    static {
        boolean loaded;
        try {
            System.loadLibrary("clipvault");
            loaded = true;
        } catch (UnsatisfiedLinkError error) {
            loaded = false;
        }
        NATIVE_AVAILABLE = loaded;
    }

    private NativeClassifier() {}

    @NonNull
    public static TextAnalysis analyze(@NonNull String text) {
        if (NATIVE_AVAILABLE) {
            String[] result = analyzeNative(text);
            if (result != null && result.length == 5) {
                String normalized = result[0] == null ? "" : result[0];
                try {
                    int flags = Integer.parseInt(result[1]);
                    boolean sensitive = "1".equals(result[2]);
                    return new TextAnalysis(
                            normalized,
                            flags,
                            sensitive,
                            result[3] == null ? "" : result[3],
                            result[4] == null ? "" : result[4],
                            normalized.codePointCount(0, normalized.length()));
                } catch (NumberFormatException ignored) {
                    // A mismatched native library must not break capture.
                }
            }
        }
        return fallbackAnalyze(text);
    }

    public static int classify(@NonNull String text) {
        return analyze(text).flags;
    }

    @NonNull
    public static String normalize(@NonNull String text) {
        return analyze(text).normalized;
    }

    public static boolean isSensitive(@NonNull String text) {
        return analyze(text).sensitive;
    }

    @NonNull
    static TextAnalysis fallbackAnalyze(@NonNull String input) {
        String normalized = input.trim().replace("\r", "").replaceAll("\n{3,}", "\n\n");
        String lower = normalized.toLowerCase(Locale.ROOT);
        int flags = 0;
        String canonicalUrl = "";
        String domain = "";
        Matcher urlMatcher = URL.matcher(normalized);
        if (urlMatcher.find()) {
            canonicalUrl = trimUrl(urlMatcher.group());
            if (canonicalUrl.regionMatches(true, 0, "www.", 0, 4)) canonicalUrl = "https://" + canonicalUrl;
            flags |= LINK;
            try {
                domain = URI.create(canonicalUrl).getHost();
                if (domain == null) domain = "";
                domain = domain.toLowerCase(Locale.ROOT);
                if (domain.startsWith("www.")) domain = domain.substring(4);
            } catch (IllegalArgumentException ignored) {
                domain = "";
            }
        }
        if (domainMatches(domain, "instagram.com") || domainMatches(domain, "instagr.am")) flags |= INSTAGRAM | LINK;
        if (domainMatches(domain, "youtube.com") || domainMatches(domain, "youtu.be")) flags |= YOUTUBE | LINK;
        if (domainMatches(domain, "telegram.me") || domainMatches(domain, "t.me")) flags |= TELEGRAM | LINK;
        if (domainMatches(domain, "tiktok.com")) flags |= TIKTOK | LINK;
        if (domainMatches(domain, "twitter.com") || domainMatches(domain, "x.com")) flags |= X_TWITTER | LINK;
        if (domainMatches(domain, "github.com") || domainMatches(domain, "githubusercontent.com")) flags |= GITHUB | LINK;
        boolean persian = normalized.matches("(?s).*[\\u0600-\\u06ff].*");
        boolean english = normalized.matches("(?s).*[A-Za-z].*");
        if (persian) flags |= PERSIAN;
        if (english) flags |= ENGLISH;
        if (persian && english) flags |= MIXED_LANGUAGE;
        int count = normalized.codePointCount(0, normalized.length());
        if (count >= 280 || normalized.chars().filter(value -> value == '\n').count() >= 5) flags |= LONG_TEXT;
        if (normalized.matches("(?s).*\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{1,4}.*") ||
                lower.contains("today") || lower.contains("امروز")) flags |= DATE;
        if (EMAIL_PATTERN.matcher(normalized).find()) flags |= EMAIL;
        if (hasPhone(normalized)) flags |= PHONE;
        String compact = normalized.trim();
        if ((compact.startsWith("{") && compact.endsWith("}")) ||
                (compact.startsWith("[") && compact.endsWith("]"))) flags |= JSON;
        if (lower.contains("#include") || lower.contains("function ") || lower.contains("class ") ||
                lower.contains("const ") || lower.contains("fun ") || lower.contains("public static")) flags |= CODE;
        boolean sensitive = looksSensitive(normalized, lower);
        if (sensitive) flags |= SENSITIVE;
        if (flags == 0) flags = OTHER;
        return new TextAnalysis(normalized, flags, sensitive, canonicalUrl, domain, count);
    }

    private static boolean looksSensitive(String text, String lower) {
        if (lower.contains("password") || lower.contains("passcode") || lower.contains("verification code") ||
                lower.contains("one-time") || lower.contains("otp") || lower.contains("2fa") ||
                lower.contains("api_key") || lower.contains("api key") || lower.contains("secret token") ||
                lower.contains("private key") ||
                lower.contains("کد تایید") || lower.contains("کد تأیید") || lower.contains("رمز عبور") ||
                lower.contains("رمز یکبار")) return true;
        java.util.ArrayList<Integer> digits = new java.util.ArrayList<>();
        int nonSeparator = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            int digit = Character.digit(codePoint, 10);
            if (digit >= 0) {
                digits.add(digit);
                nonSeparator++;
            } else if (!Character.isWhitespace(codePoint) && "-()".indexOf(codePoint) < 0) {
                nonSeparator++;
            }
            offset += Character.charCount(codePoint);
        }
        if (digits.size() == nonSeparator && digits.size() >= 4 && digits.size() <= 8) return true;
        return luhnValid(digits);
    }

    private static boolean luhnValid(java.util.List<Integer> digits) {
        if (digits.size() < 13 || digits.size() > 19) return false;
        int sum = 0;
        boolean doubled = false;
        for (int index = digits.size() - 1; index >= 0; index--) {
            int value = digits.get(index);
            if (doubled && (value *= 2) > 9) value -= 9;
            sum += value;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }

    private static boolean hasPhone(String text) {
        int digits = 0;
        int width = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            if (Character.digit(codePoint, 10) >= 0) {
                digits++;
                width++;
            } else if (codePoint == '+' || codePoint == '-' || codePoint == '(' ||
                    codePoint == ')' || codePoint == ' ') {
                if (digits > 0) width++;
            } else {
                if (digits >= 7 && digits <= 15 && width <= 22) return true;
                digits = 0;
                width = 0;
            }
            offset += Character.charCount(codePoint);
        }
        return digits >= 7 && digits <= 15 && width <= 22;
    }

    private static boolean domainMatches(String domain, String base) {
        return domain.equals(base) || domain.endsWith("." + base);
    }

    private static String trimUrl(String value) {
        int end = value.length();
        while (end > 0 && ".,;:!?)]}".indexOf(value.charAt(end - 1)) >= 0) end--;
        return value.substring(0, end);
    }

    private static native String[] analyzeNative(String text);
}
