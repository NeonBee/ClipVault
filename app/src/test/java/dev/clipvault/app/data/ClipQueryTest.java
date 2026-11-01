package dev.clipvault.app.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ClipQueryTest {
    @Test
    public void pageBoundsAreDefensive() {
        ClipQuery query = ClipQuery.builder().page(10_000, -20).build();
        assertEquals(200, query.limit);
        assertEquals(0, query.offset);
    }

    @Test
    public void folderMasksAreIndependentAndOffsetIsPreserved() {
        ClipQuery query = ClipQuery.builder()
                .requiredFlag(4)
                .excludedFlags(8)
                .page(30, 90)
                .build();
        assertEquals(4, query.requiredFlag);
        assertEquals(8, query.excludedFlags);
        assertEquals(30, query.limit);
        assertEquals(90, query.offset);
    }

    @Test
    public void advancedFiltersNormalizeDomainAndKeepTag() {
        ClipQuery query = ClipQuery.builder().domain("  GitHub.COM ").tagId(42L).build();
        assertEquals("github.com", query.domain);
        assertEquals(Long.valueOf(42), query.tagId);
    }
}
