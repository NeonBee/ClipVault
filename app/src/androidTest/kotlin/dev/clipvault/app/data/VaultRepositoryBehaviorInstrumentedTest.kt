package dev.clipvault.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultRepositoryBehaviorInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var repository: VaultRepository

    @Before fun setUp() {
        context.deleteDatabase("clipvault.db")
        repository = VaultRepository(context, ByteArray(32) { (it + 31).toByte() })
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase("clipvault.db")
    }

    @Test fun duplicateCaptureMergesTimesAndCount() {
        val id = repository.insert("  duplicate text  ", 2_000)
        assertEquals(id, repository.insert("duplicate text", 5_000))
        val item = repository.find(id)!!
        assertEquals(1, repository.count())
        assertEquals(2, item.captureCount)
        assertEquals(2_000, item.firstCapturedAt)
        assertEquals(5_000, item.lastCapturedAt)
    }

    @Test fun encryptedFtsAndTagFiltersFindMetadata() {
        val id = repository.insert("https://github.com/example/private", 1_000)
        repository.edit(id, "https://github.com/example/private", "Architecture", "offline design note")
        val tagId = repository.createTag("security", "teal")
        repository.setTags(id, listOf(tagId))

        assertEquals(id, repository.query(ClipQuery.builder().search("archit").build()).items.single().id)
        assertEquals(id, repository.query(ClipQuery.builder().search("offline").build()).items.single().id)
        assertEquals(id, repository.query(ClipQuery.builder().domain("github.com").build()).items.single().id)
        assertEquals(id, repository.query(ClipQuery.builder().tagId(tagId).build()).items.single().id)
        assertTrue(repository.query(ClipQuery.builder().tagId(tagId + 1).build()).items.isEmpty())
    }

    @Test fun retentionMovesOnlyUnpinnedClipsAndTrashCanRestoreOrPurge() {
        val old = repository.insert("old", 1_000)
        val pinned = repository.insert("pinned", 1_000)
        val favorite = repository.insert("favorite", 1_000)
        repository.setPinned(pinned, true)
        repository.setFavorite(favorite, true)

        assertEquals(2, repository.softDeleteOlderThan(2_000, 10_000))
        assertEquals(1, repository.count())
        val trashed = repository.query(ClipQuery.builder().trash(true).build()).items
        assertTrue(trashed.any { it.id == old })
        assertTrue(trashed.any { it.id == favorite })
        assertFalse(trashed.any { it.id == pinned })

        assertEquals(1, repository.restore(listOf(old)))
        assertEquals(1, repository.purgeTrashOlderThan(10_001))
        assertEquals(2, repository.count())
        assertTrue(repository.query(ClipQuery.builder().trash(true).build()).items.isEmpty())
    }
}
