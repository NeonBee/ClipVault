package dev.clipvault.app.data;

import androidx.annotation.NonNull;

public final class CaptureRule {
    public enum Type { DOMAIN, CONTAINS, PREFIX, REGEX, MIN_LENGTH, MAX_LENGTH }

    public final long id;
    @NonNull public final Type type;
    @NonNull public final String pattern;
    public final boolean enabled;
    public final long matchCount;
    public final long lastMatchedAt;

    public CaptureRule(long id, @NonNull Type type, @NonNull String pattern,
                       boolean enabled, long matchCount, long lastMatchedAt) {
        this.id = id;
        this.type = type;
        this.pattern = pattern;
        this.enabled = enabled;
        this.matchCount = matchCount;
        this.lastMatchedAt = lastMatchedAt;
    }
}
