package dev.clipvault.app.data;

import androidx.annotation.NonNull;

public final class CollectionRecord {
    public final long id;
    @NonNull public final String name;
    @NonNull public final String colorKey;
    public final int sortOrder;
    public final int clipCount;

    public CollectionRecord(long id, @NonNull String name, @NonNull String colorKey, int sortOrder, int clipCount) {
        this.id = id;
        this.name = name;
        this.colorKey = colorKey;
        this.sortOrder = sortOrder;
        this.clipCount = clipCount;
    }
}
