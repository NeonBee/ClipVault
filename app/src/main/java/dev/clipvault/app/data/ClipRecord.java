package dev.clipvault.app.data;

import androidx.annotation.NonNull;

public final class ClipRecord {
    public final long id;
    @NonNull public final String content;
    public final long createdAt;
    public final int flags;
    public final boolean favorite;

    public ClipRecord(long id, @NonNull String content, long createdAt, int flags, boolean favorite) {
        this.id = id;
        this.content = content;
        this.createdAt = createdAt;
        this.flags = flags;
        this.favorite = favorite;
    }
}
