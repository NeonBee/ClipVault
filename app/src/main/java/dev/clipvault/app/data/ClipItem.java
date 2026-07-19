package dev.clipvault.app.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class ClipItem {
    public final long id;
    @NonNull public final String content;
    @NonNull public final String title;
    @NonNull public final String note;
    @NonNull public final String domain;
    public final long firstCapturedAt;
    public final long lastCapturedAt;
    public final int captureCount;
    public final int characterCount;
    public final int flags;
    public final boolean favorite;
    public final boolean pinned;
    @Nullable public final Long collectionId;
    @Nullable public final Long deletedAt;

    public ClipItem(
            long id,
            @NonNull String content,
            @NonNull String title,
            @NonNull String note,
            @NonNull String domain,
            long firstCapturedAt,
            long lastCapturedAt,
            int captureCount,
            int characterCount,
            int flags,
            boolean favorite,
            boolean pinned,
            @Nullable Long collectionId,
            @Nullable Long deletedAt) {
        this.id = id;
        this.content = content;
        this.title = title;
        this.note = note;
        this.domain = domain;
        this.firstCapturedAt = firstCapturedAt;
        this.lastCapturedAt = lastCapturedAt;
        this.captureCount = captureCount;
        this.characterCount = characterCount;
        this.flags = flags;
        this.favorite = favorite;
        this.pinned = pinned;
        this.collectionId = collectionId;
        this.deletedAt = deletedAt;
    }
}
