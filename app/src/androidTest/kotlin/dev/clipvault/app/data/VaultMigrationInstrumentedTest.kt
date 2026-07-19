package dev.clipvault.app.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultMigrationInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Before fun clean() { context.deleteDatabase("clipvault.db") }
    @After fun finish() { context.deleteDatabase("clipvault.db") }

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
        }
    }
}
