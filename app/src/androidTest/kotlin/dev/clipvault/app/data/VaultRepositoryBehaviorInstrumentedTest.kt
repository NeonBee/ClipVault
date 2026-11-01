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

    @Test fun manualFolderOverrideSurvivesAutomaticBackfillAndProtectsRawSecurityFlags() {
        val id = repository.insert("입금계좌 안내", 1_000)
        val original = repository.find(id)!!
        val finance = dev.clipvault.app.nativecore.NativeClassifier.FINANCE
        val identity = dev.clipvault.app.nativecore.NativeClassifier.IDENTITY
        assertTrue(original.flags and finance != 0)
        repository.setManualCategoryOverride(id, identity, finance)
        val corrected = repository.query(ClipQuery.builder().build()).items.single()
        assertTrue(corrected.flags and finance != 0) // raw classifier result unchanged
        assertTrue(corrected.browseFlags and identity != 0)
        assertEquals(0, corrected.browseFlags and finance)
        assertEquals(1, repository.query(ClipQuery.builder().requiredFlag(identity).build()).items.size)
        assertTrue(repository.query(ClipQuery.builder().requiredFlag(finance).build()).items.isEmpty())
        assertEquals(1, repository.allManualCategoryOverrides().size)
        repository.backfillBrowseSignalsAfter(0L, 32)
        val afterBackfill = repository.query(ClipQuery.builder().build()).items.single()
        assertEquals(corrected.browseFlags, afterBackfill.browseFlags)
        assertEquals(corrected.flags, afterBackfill.flags)
        repository.setManualCategoryOverride(id, 0, 0)
        assertTrue(repository.allManualCategoryOverrides().isEmpty())
        assertEquals(repository.find(id)!!.flags,
            repository.query(ClipQuery.builder().build()).items.single().browseFlags)
    }

    @Test fun sensitiveSecurityFlagCannotBeErasedByManualFolderOverrides() {
        val id = repository.insert("password test", 1_000)
        val sensitive = dev.clipvault.app.nativecore.NativeClassifier.SENSITIVE
        assertTrue(repository.find(id)!!.flags and sensitive != 0)
        assertTrue(runCatching { repository.setManualCategoryOverride(id, 0, sensitive) }.isFailure)
        repository.setManualCategoryOverride(id,
            dev.clipvault.app.nativecore.NativeClassifier.CREDENTIAL, 0)
        val clip = repository.query(ClipQuery.builder().build()).items.single()
        assertTrue(clip.flags and sensitive != 0)
        assertTrue(clip.browseFlags and sensitive != 0)
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
