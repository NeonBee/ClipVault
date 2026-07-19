package dev.clipvault.app.backup

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.clipvault.app.data.ClipQuery
import dev.clipvault.app.data.VaultRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class VaultBackupInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val backupFile by lazy { File(context.cacheDir, "instrumented-backup.cvault") }
    private lateinit var repository: VaultRepository
    private lateinit var manager: VaultBackupManager

    @Before fun setUp() {
        context.deleteDatabase("clipvault.db")
        backupFile.delete()
        repository = VaultRepository(context, ByteArray(32) { (it + 11).toByte() })
        manager = VaultBackupManager(context)
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase("clipvault.db")
        backupFile.delete()
    }

    @Test fun encryptedBackupRoundTripsAndMergesByContentHash() = runBlocking {
        val firstId = repository.insert("https://github.com/clipvault/project", 1_000)
        val secondId = repository.insert("سلام ClipVault", 2_000)
        repository.setFavorite(firstId, true)
        repository.setPinned(secondId, true)
        val collectionId = repository.createCollection("Research", "violet")
        val tagId = repository.createTag("offline", "teal")
        repository.setCollection(listOf(firstId), collectionId)
        repository.setTags(firstId, listOf(tagId))

        val uri = Uri.fromFile(backupFile)
        val manifest = manager.exportVault(uri, "correct horse battery".toCharArray(), repository)
        assertEquals(2, manifest.clipCount)
        assertTrue(backupFile.readBytes().take(5).toByteArray().contentEquals("CVLT2".toByteArray()))

        repository.insert("temporary", 3_000)
        val result = manager.importVault(uri, "correct horse battery".toCharArray(), repository, replace = true)
        assertEquals(2, result.importedClips)
        assertEquals(2, repository.count())
        assertEquals(1, repository.collections().size)
        assertEquals(1, repository.tags().size)

        manager.importVault(uri, "correct horse battery".toCharArray(), repository, replace = false)
        assertEquals(2, repository.count())
        val clips = repository.query(ClipQuery.builder().page(20, 0).build()).items
        assertTrue(clips.any { it.favorite })
        assertTrue(clips.any { it.pinned })
    }

    @Test fun wrongPassphraseAndCorruptionAreRejected() = runBlocking {
        repository.insert("encrypted payload", 1_000)
        val uri = Uri.fromFile(backupFile)
        manager.exportVault(uri, "correct horse battery".toCharArray(), repository)

        assertTrue(runCatching {
            manager.inspect(uri, "wrong horse battery!".toCharArray())
        }.isFailure)

        val bytes = backupFile.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x55).toByte()
        backupFile.writeBytes(bytes)
        assertTrue(runCatching {
            manager.inspect(uri, "correct horse battery".toCharArray())
        }.isFailure)
    }

    @Test fun unknownContainerVersionIsRejectedBeforeDecryption() = runBlocking {
        backupFile.writeBytes("CVLT2".toByteArray() + ByteArray(32))
        RandomAccessFile(backupFile, "rw").use { file -> file.seek(5); file.writeInt(99) }
        assertTrue(runCatching {
            manager.inspect(Uri.fromFile(backupFile), "correct horse battery".toCharArray())
        }.exceptionOrNull()?.message.orEmpty().contains("version", ignoreCase = true))
    }
}
