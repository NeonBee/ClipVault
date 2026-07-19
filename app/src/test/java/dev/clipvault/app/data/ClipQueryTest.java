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
    public void advancedFiltersNormalizeDomainAndKeepTag() {
        ClipQuery query = ClipQuery.builder().domain("  GitHub.COM ").tagId(42L).build();
        assertEquals("github.com", query.domain);
        assertEquals(Long.valueOf(42), query.tagId);
    }
}
