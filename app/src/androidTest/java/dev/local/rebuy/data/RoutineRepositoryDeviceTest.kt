package dev.local.rebuy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.rebuy.domain.CompletionResult
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the transaction and SQLite unique-index guarantees on an actual Android runtime. */
@RunWith(AndroidJUnit4::class)
class RoutineRepositoryDeviceTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoutineRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).build()
        repository = RoutineRepository(
            database, Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun concurrentCompletionIsAtomicAndHistorySurvivesArchive() = runBlocking {
        val id = repository.saveItem(null, "화장지", "https://www.coupang.com/vp/products/123", 2, 14, LocalDate.of(2026, 10, 21).toEpochDay())
        val cycle = requireNotNull(repository.getItem(id)).cycleId
        val results = List(12) { async(Dispatchers.Default) { repository.completePurchase(id, cycle) } }.awaitAll()
        assertEquals(1, results.count { it == CompletionResult.Completed })
        assertEquals(11, results.count { it == CompletionResult.AlreadyRecorded })
        assertEquals(LocalDate.of(2026, 11, 4).toEpochDay(), repository.getItem(id)?.nextPurchaseEpochDay)
        repository.archiveItem(id)
        assertTrue(repository.items.first().isEmpty())
        assertEquals(1, repository.history.first().size)
        assertEquals("화장지", repository.history.first().single().title)
    }

    @Test
    fun uniqueIndexRejectsDuplicateEvenOutsideRepository() = runBlocking {
        val record = PurchaseRecord(
            itemId = "item", cycleId = "cycle", title = "커피", productUrl = "https://coupang.com/vp/products/1",
            quantity = 1, scheduledEpochDay = 20000L, completedAtEpochMillis = 1234L,
        )
        assertNotEquals(-1L, database.purchaseDao().insertOnce(record))
        assertEquals(-1L, database.purchaseDao().insertOnce(record.copy(id = "another-record")))
        assertEquals(1, repository.history.first().size)
    }
}
