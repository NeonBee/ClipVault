package dev.clipvault.app.data;

public final class VaultStats {
    public final int activeCount;
    public final int trashCount;
    public final int favoriteCount;
    public final int pinnedCount;
    public final int linkCount;
    public final int todayCount;
    public final long totalCharacters;
    public final long duplicateCaptures;

    public VaultStats(int activeCount, int trashCount, int favoriteCount, int pinnedCount,
                      int linkCount, int todayCount, long totalCharacters, long duplicateCaptures) {
        this.activeCount = activeCount;
        this.trashCount = trashCount;
        this.favoriteCount = favoriteCount;
        this.pinnedCount = pinnedCount;
        this.linkCount = linkCount;
        this.todayCount = todayCount;
        this.totalCharacters = totalCharacters;
        this.duplicateCaptures = duplicateCaptures;
    }
}
