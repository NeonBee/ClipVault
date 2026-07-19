package dev.clipvault.app.data;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

public final class ClipPage {
    @NonNull public final List<ClipItem> items;
    public final int nextOffset;
    public final boolean hasMore;

    public ClipPage(@NonNull List<ClipItem> items, int nextOffset, boolean hasMore) {
        this.items = Collections.unmodifiableList(items);
        this.nextOffset = nextOffset;
        this.hasMore = hasMore;
    }
}
