package dev.clipvault.app.data

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class VaultPerformanceInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = ByteArray(32) { (it + 71).toByte() }
    private lateinit var repository: VaultRepository

    @Before fun setUp() {
        context.deleteDatabase("clipvault.db")
        repository = VaultRepository(context, key.copyOf())
    }

    @After fun tearDown() {
        repository.close()
        context.deleteDatabase("clipvault.db")
    }

    @Test fun firstPageAndIndexedSearchStayUnderAcceptanceBudgetWithTwentyThousandClips() {
        repository.runInTransaction {
            repeat(20_000) { index ->
                repository.insert("entry $index searchable needle$index", index.toLong() + 1)
            }
        }
        repository.close()
        repository = VaultRepository(context, key.copyOf())

        val firstPageStart = SystemClock.elapsedRealtimeNanos()
        val firstPage = repository.query(ClipQuery.builder().page(50, 0).build())
        val firstPageMs = (SystemClock.elapsedRealtimeNanos() - firstPageStart) / 1_000_000.0

        val searchStart = SystemClock.elapsedRealtimeNanos()
        val search = repository.query(ClipQuery.builder().search("needle19999").page(50, 0).build())
        val searchMs = (SystemClock.elapsedRealtimeNanos() - searchStart) / 1_000_000.0

        assertEquals(50, firstPage.items.size)
        assertEquals("entry 19999 searchable needle19999", search.items.single().content)
        assertTrue("First page took ${firstPageMs}ms", firstPageMs <= 250.0)
        assertTrue("Indexed search took ${searchMs}ms", searchMs <= 250.0)
    }
}
