package dev.clipvault.app.data;

public final class ClipTagLink {
    public final long clipId;
    public final long tagId;

    public ClipTagLink(long clipId, long tagId) {
        this.clipId = clipId;
        this.tagId = tagId;
    }
}
