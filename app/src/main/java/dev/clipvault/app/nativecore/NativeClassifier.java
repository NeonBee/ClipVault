package dev.clipvault.app.nativecore;

import androidx.annotation.NonNull;

import java.util.Locale;

public final class NativeClassifier {
    public static final int LINK = 1;
    public static final int INSTAGRAM = 1 << 1;
    public static final int YOUTUBE = 1 << 2;
    public static final int PERSIAN = 1 << 3;
    public static final int ENGLISH = 1 << 4;
    public static final int LONG_TEXT = 1 << 5;
    public static final int DATE = 1 << 6;
    public static final int OTHER = 1 << 7;

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

    public static int classify(@NonNull String text) {
        if (NATIVE_AVAILABLE) return classifyNative(text);
        return fallbackClassify(text);
    }

    @NonNull
    public static String normalize(@NonNull String text) {
        if (NATIVE_AVAILABLE) return normalizeNative(text);
        return text.trim().replace("\r", "").replaceAll("\n{3,}", "\n\n");
    }

    public static boolean isSensitive(@NonNull String text) {
        if (NATIVE_AVAILABLE) return isSensitiveNative(text);
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.matches("\\s*\\d{4,8}\\s*") || lower.contains("password") ||
                lower.contains("otp") || lower.contains("کد تایید") || lower.contains("رمز عبور");
    }

    private static int fallbackClassify(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        int flags = 0;
        if (lower.contains("http://") || lower.contains("https://") || lower.contains("www.")) flags |= LINK;
        if (lower.contains("instagram.com") || lower.contains("instagr.am")) flags |= INSTAGRAM | LINK;
        if (lower.contains("youtube.com") || lower.contains("youtu.be")) flags |= YOUTUBE | LINK;
        if (text.matches("(?s).*[\\u0600-\\u06ff].*")) flags |= PERSIAN;
        if (text.matches("(?s).*[A-Za-z].*")) flags |= ENGLISH;
        if (text.codePointCount(0, text.length()) >= 280) flags |= LONG_TEXT;
        if (text.matches("(?s).*\\d{1,4}[/.-]\\d{1,2}[/.-]\\d{1,4}.*")) flags |= DATE;
        return flags == 0 ? OTHER : flags;
    }

    private static native int classifyNative(String text);
    private static native String normalizeNative(String text);
    private static native boolean isSensitiveNative(String text);
}
