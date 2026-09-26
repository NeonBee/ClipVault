package dev.clipvault.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Forensic deletion checks for the FTS5 index. The marker must disappear from the decrypted
 * `clips_fts_data` / `clips_fts_idx` shadow tables, not only from MATCH results. Markers use a
 * prefix no filler term shares, so a leftover would be stored in full rather than prefix-compressed.
 * WAL frames are not inspected here: close() checkpoints them; VaultRepository also truncates the
 * WAL after every hard delete.
 */
@RunWith(AndroidJUnit4::class)
class VaultDeletionHardeningInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = ByteArray(32) { (it + 7).toByte() }
    private val marker = "zqxsecretmarker7731"

    @Before fun clean() {
        System.loadLibrary("sqlcipher")
        context.deleteDatabase("clipvault.db")
    }

    @After fun finish() { context.deleteDatabase("clipvault.db") }

    @Test fun freshVaultStartsAtSchemaThreeWithFtsSecureDelete() {
        VaultRepository(context, key.copyOf()).use { assertTrue(it.isFtsSecureDeleteEnabled) }
        rawDatabase { db -> assertEquals(VaultRepository.SCHEMA_VERSION, userVersion(db)) }
    }

    @Test fun permanentDeleteRemovesTermsFromFtsShadowTables() {
        VaultRepository(context, key.copyOf()).use { repository ->
            insertFiller(repository)
            val id = repository.insert("$marker private token", 5_000)
            assertEquals(id, repository.query(search(marker)).items.single().id)

            assertEquals(1, repository.moveToTrash(listOf(id), "manual", 6_000))
            // Trash keeps the clip recoverable and searchable by design.
            assertEquals(id, repository.query(search(marker, trash = true)).items.single().id)

            assertEquals(1, repository.permanentlyDelete(listOf(id)))
            assertTrue(repository.query(search(marker)).items.isEmpty())
            assertTrue(repository.query(search(marker, trash = true)).items.isEmpty())
        }
        rawDatabase { db -> assertEquals(0, shadowRowsContaining(db, marker)) }
        VaultRepository(context, key.copyOf()).use { repository ->
            assertTrue(repository.query(search(marker)).items.isEmpty())
            assertEquals(40, repository.query(search("filler")).items.size)
        }
    }

    @Test fun trashPurgeAndEditDoNotLeaveOldTerms() {
        VaultRepository(context, key.copyOf()).use { repository ->
            insertFiller(repository)
            val purged = repository.insert("$marker purge", 1_000)
            repository.moveToTrash(listOf(purged), "manual", 2_000)
            assertEquals(1, repository.purgeTrashOlderThan(3_000))

            val edited = repository.insert("editedsecret4412 original", 1_000)
            assertTrue(repository.edit(edited, "replacement text", "", ""))
            assertTrue(repository.query(search("editedsecret4412")).items.isEmpty())
        }
        rawDatabase { db ->
            assertEquals(0, shadowRowsContaining(db, marker))
            assertEquals(0, shadowRowsContaining(db, "editedsecret4412"))
        }
    }

    @Test fun schemaTwoVaultMigratesAndPurgesLegacyFtsTraces() {
        VaultRepository(context, key.copyOf()).use { insertFiller(it) }
        // Recreate a v2 vault: same tables, FTS5 secure-delete off, a legacy delete tombstone.
        rawDatabase { db ->
            db.execSQL("INSERT INTO clips_fts(clips_fts,rank) VALUES('secure-delete',0)")
            db.execSQL(
                "INSERT INTO clips(content,content_hash,created_at,flags,first_captured_at,last_captured_at) " +
                    "VALUES('$marker legacy','legacy-hash',1000,0,1000,1000)",
            )
            db.execSQL("DELETE FROM clips WHERE content_hash='legacy-hash'")
            db.execSQL("PRAGMA user_version=2")
            assertTrue("precondition: v2 delete leaves FTS5 traces", shadowRowsContaining(db, marker) > 0)
        }

        VaultRepository(context, key.copyOf()).use { repository ->
            assertTrue(repository.isFtsSecureDeleteEnabled)
            assertEquals(40, repository.query(search("filler")).items.size)
            assertTrue(repository.query(search(marker)).items.isEmpty())
        }
        rawDatabase { db ->
            assertEquals(3, userVersion(db))
            assertEquals(0, shadowRowsContaining(db, marker))
        }
    }

    private fun insertFiller(repository: VaultRepository) {
        repeat(40) { repository.insert("filler entry number $it alpha", 1_000L + it) }
    }

    private fun search(value: String, trash: Boolean = false) =
        ClipQuery.builder().search(value).trash(trash).build()

    private fun rawDatabase(block: (SQLiteDatabase) -> Unit) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("clipvault.db"), key.copyOf(), null, null, null)
            .use(block)
    }

    private fun userVersion(db: SQLiteDatabase): Int =
        db.rawQuery("PRAGMA user_version", arrayOf()).use { it.moveToFirst(); it.getInt(0) }

    private fun shadowRowsContaining(db: SQLiteDatabase, term: String): Int {
        val needle = term.toByteArray(Charsets.UTF_8)
        var hits = 0
        // Leaf pages hold (prefix-compressed) terms; _idx holds page-boundary term prefixes.
        for (sql in listOf("SELECT block FROM clips_fts_data", "SELECT term FROM clips_fts_idx")) {
            db.rawQuery(sql, arrayOf()).use { cursor ->
                while (cursor.moveToNext()) {
                    val block = if (cursor.isNull(0)) null else cursor.getBlob(0)
                    if (block != null && contains(block, needle)) hits++
                }
            }
        }
        return hits
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) if (haystack[start + offset] != needle[offset]) continue@outer
            return true
        }
        return false
    }
}
