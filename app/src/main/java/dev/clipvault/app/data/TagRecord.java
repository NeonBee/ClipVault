package dev.clipvault.app.data;

import androidx.annotation.NonNull;

public final class TagRecord {
    public final long id;
    @NonNull public final String name;
    @NonNull public final String colorKey;
    public final int clipCount;

    public TagRecord(long id, @NonNull String name, @NonNull String colorKey, int clipCount) {
        this.id = id;
        this.name = name;
        this.colorKey = colorKey;
        this.clipCount = clipCount;
    }
}
