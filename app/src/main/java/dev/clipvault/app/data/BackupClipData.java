package dev.clipvault.app.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class BackupClipData {
    public final long sourceId;
    @NonNull public final String content;
    @NonNull public final String title;
    @NonNull public final String note;
    public final long firstCapturedAt;
    public final long lastCapturedAt;
    public final int captureCount;
    public final boolean favorite;
    public final boolean pinned;
    @Nullable public final Long deletedAt;

    public BackupClipData(long sourceId, @NonNull String content, @NonNull String title,
                          @NonNull String note, long firstCapturedAt, long lastCapturedAt,
                          int captureCount, boolean favorite, boolean pinned, @Nullable Long deletedAt) {
        this.sourceId = sourceId;
        this.content = content;
        this.title = title;
        this.note = note;
        this.firstCapturedAt = firstCapturedAt;
        this.lastCapturedAt = lastCapturedAt;
        this.captureCount = captureCount;
        this.favorite = favorite;
        this.pinned = pinned;
        this.deletedAt = deletedAt;
    }
}
