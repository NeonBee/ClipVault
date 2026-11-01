package dev.clipvault.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultMigrationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Before fun clean() { context.deleteDatabase("clipvault.db") }
    @After fun finish() { context.deleteDatabase("clipvault.db") }

    @Test fun browseBackfillUpgradesOnlyDerivedFlagsWithoutLosingLegacyRows() {
        System.loadLibrary("sqlcipher")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("clipvault.db"), key, null, null, null).use { db ->
            db.execSQL("CREATE TABLE clips(id INTEGER PRIMARY KEY AUTOINCREMENT,content TEXT NOT NULL,content_hash TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL,flags INTEGER NOT NULL,favorite INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO clips(content,content_hash,created_at,flags,favorite) VALUES('입금계좌 안내','synthetic-old-hash',1000,128,1)")
            db.execSQL("PRAGMA user_version=1")
        }
        VaultRepository(context, key.copyOf()).use { repository ->
            val before = repository.query(ClipQuery.builder().build()).items.single()
            assertEquals(128, before.flags) // OTHER, no browse flags in a legacy clip.
            val next = repository.backfillBrowseSignalsAfter(0L, 32)
            assertTrue(next > 0)
            assertEquals(-1L, repository.backfillBrowseSignalsAfter(next, 32))
            val after = repository.query(ClipQuery.builder().build()).items.single()
            assertEquals(before.content, after.content)
            assertEquals(before.title, after.title)
            assertEquals(before.favorite, after.favorite)
            assertTrue(after.flags and dev.clipvault.app.nativecore.NativeClassifier.FINANCE != 0)
            assertTrue(after.flags and dev.clipvault.app.nativecore.NativeClassifier.KOREAN != 0)
            assertTrue(after.flags and dev.clipvault.app.nativecore.NativeClassifier.OTHER != 0)
        }
    }

    @Test fun versionOneMigratesWithoutLosingContent() {
        System.loadLibrary("sqlcipher")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath("clipvault.db"), key, null, null, null).use { db ->
            db.execSQL("CREATE TABLE clips(id INTEGER PRIMARY KEY AUTOINCREMENT,content TEXT NOT NULL,content_hash TEXT NOT NULL UNIQUE,created_at INTEGER NOT NULL,flags INTEGER NOT NULL,favorite INTEGER NOT NULL DEFAULT 0)")
            db.execSQL("INSERT INTO clips(content,content_hash,created_at,flags,favorite) VALUES('legacy','hash',1000,1,1)")
            db.execSQL("PRAGMA user_version=1")
        }
        VaultRepository(context, key.copyOf()).use { repository ->
            assertEquals(1, repository.count())
            val item = repository.query(ClipQuery.builder().build()).items.single()
            assertEquals("legacy", item.content)
            assertEquals(1000L, item.lastCapturedAt)
            assertTrue(repository.isFtsSecureDeleteEnabled)
        }
    }
}
