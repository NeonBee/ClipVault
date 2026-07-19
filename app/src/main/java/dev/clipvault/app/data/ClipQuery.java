package dev.clipvault.app.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public final class ClipQuery {
    public enum Sort { NEWEST, OLDEST, FREQUENT, LONGEST }

    @NonNull public final String search;
    public final int requiredFlag;
    public final boolean favoritesOnly;
    public final boolean pinnedOnly;
    public final boolean trash;
    @NonNull public final String domain;
    @Nullable public final Long collectionId;
    @Nullable public final Long tagId;
    @Nullable public final Long fromTime;
    @Nullable public final Long toTime;
    @NonNull public final Sort sort;
    public final int limit;
    public final int offset;

    private ClipQuery(Builder builder) {
        search = builder.search;
        requiredFlag = builder.requiredFlag;
        favoritesOnly = builder.favoritesOnly;
        pinnedOnly = builder.pinnedOnly;
        trash = builder.trash;
        domain = builder.domain;
        collectionId = builder.collectionId;
        tagId = builder.tagId;
        fromTime = builder.fromTime;
        toTime = builder.toTime;
        sort = builder.sort;
        limit = Math.max(1, Math.min(builder.limit, 200));
        offset = Math.max(0, builder.offset);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String search = "";
        private int requiredFlag;
        private boolean favoritesOnly;
        private boolean pinnedOnly;
        private boolean trash;
        private String domain = "";
        private Long collectionId;
        private Long tagId;
        private Long fromTime;
        private Long toTime;
        private Sort sort = Sort.NEWEST;
        private int limit = 50;
        private int offset;

        public Builder search(@NonNull String value) { search = value.trim(); return this; }
        public Builder requiredFlag(int value) { requiredFlag = value; return this; }
        public Builder favoritesOnly(boolean value) { favoritesOnly = value; return this; }
        public Builder pinnedOnly(boolean value) { pinnedOnly = value; return this; }
        public Builder trash(boolean value) { trash = value; return this; }
        public Builder domain(@NonNull String value) { domain = value.trim().toLowerCase(java.util.Locale.ROOT); return this; }
        public Builder collectionId(@Nullable Long value) { collectionId = value; return this; }
        public Builder tagId(@Nullable Long value) { tagId = value; return this; }
        public Builder fromTime(@Nullable Long value) { fromTime = value; return this; }
        public Builder toTime(@Nullable Long value) { toTime = value; return this; }
        public Builder sort(@NonNull Sort value) { sort = value; return this; }
        public Builder page(int limitValue, int offsetValue) { limit = limitValue; offset = offsetValue; return this; }
        public ClipQuery build() { return new ClipQuery(this); }
    }
}
