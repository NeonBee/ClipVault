package dev.clipvault.app.nativecore;

import androidx.annotation.NonNull;

/** Immutable result produced by the deterministic on-device analyzer. */
public final class TextAnalysis {
    @NonNull public final String normalized;
    public final int flags;
    public final boolean sensitive;
    @NonNull public final String canonicalUrl;
    @NonNull public final String domain;
    public final int characterCount;

    public TextAnalysis(
            @NonNull String normalized,
            int flags,
            boolean sensitive,
            @NonNull String canonicalUrl,
            @NonNull String domain,
            int characterCount) {
        this.normalized = normalized;
        this.flags = flags;
        this.sensitive = sensitive;
        this.canonicalUrl = canonicalUrl;
        this.domain = domain;
        this.characterCount = characterCount;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    @NonNull
    public String languageCode() {
        boolean fa = has(NativeClassifier.PERSIAN);
        boolean en = has(NativeClassifier.ENGLISH);
        if (fa && en) return "mixed";
        if (fa) return "fa";
        if (en) return "en";
        return "other";
    }
}
