package dev.clipvault.app.data;

/** SQLCipher-resident user-authored folder corrections; contains no clip plaintext. */
public final class ClipCategoryOverride {
    public final long clipId;
    public final int includedFlags;
    public final int excludedFlags;

    public ClipCategoryOverride(long clipId, int includedFlags, int excludedFlags) {
        this.clipId = clipId;
        this.includedFlags = includedFlags;
        this.excludedFlags = excludedFlags;
    }
}
