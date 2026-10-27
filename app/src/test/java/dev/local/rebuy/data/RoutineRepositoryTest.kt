package dev.local.rebuy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.rebuy.domain.CompletionResult
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [28])
class RoutineRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoutineRepository
    // UTC date is Oct 21; the user's local date in Seoul is already Oct 22.
    private val clock = Clock.fixed(Instant.parse("2026-10-21T15:30:00Z"), ZoneId.of("Asia/Seoul"))

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = RoutineRepository(database, clock)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun savesValidatedNormalizedItem() = runTest {
        val id = save(title = "  생수  ", url = "  HTTPS://WWW.COUPANG.COM/vp/products/123  ")
        val item = requireNotNull(repository.getItem(id))
        assertEquals("생수", item.title)
        assertEquals("https://www.coupang.com/vp/products/123", item.productUrl)
        assertTrue(item.enabled)
        assertFalse(item.archived)
        assertNull(item.purchaseOption)
        assertNull(item.maxTotalWon)
        assertEquals(clock.millis(), item.createdAtEpochMillis)
        assertEquals(listOf(item), repository.items.first())
    }

    @Test
    fun completionCreatesSnapshotAndUsesLocalActualDate() = runTest {
        val id = save(due = "2026-09-01", interval = 7)
        val before = requireNotNull(repository.getItem(id))
        assertEquals(CompletionResult.Completed, repository.completePurchase(id, before.cycleId))
        val after = requireNotNull(repository.getItem(id))
        val record = repository.history.first().single()
        assertEquals(day("2026-10-29"), after.nextPurchaseEpochDay)
        assertNotEquals(before.cycleId, after.cycleId)
        assertEquals(before.cycleId, record.cycleId)
        assertEquals(before.title, record.title)
        assertEquals(before.productUrl, record.productUrl)
        assertEquals(before.quantity, record.quantity)
        assertEquals(day("2026-09-01"), record.scheduledEpochDay)
        assertEquals(clock.millis(), record.completedAtEpochMillis)
    }

    @Test
    fun completionReadsOneInstantAcrossMidnight() = runTest {
        val id = save()
        val item = requireNotNull(repository.getItem(id))
        val boundaryMillis = Instant.parse("2026-10-21T14:59:59.999Z").toEpochMilli()
        val ticks = AtomicLong(boundaryMillis)
        val advancingClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("Asia/Seoul")
            override fun withZone(zone: ZoneId): Clock = Clock.fixed(Instant.ofEpochMilli(ticks.get()), zone)
            override fun instant(): Instant = Instant.ofEpochMilli(ticks.getAndIncrement())
        }
        val boundaryRepository = RoutineRepository(database, advancingClock)
        assertEquals(CompletionResult.Completed, boundaryRepository.completePurchase(id, item.cycleId))
        assertEquals(boundaryMillis, boundaryRepository.history.first().single().completedAtEpochMillis)
        assertEquals(day("2026-10-28"), boundaryRepository.getItem(id)?.nextPurchaseEpochDay)
    }

    @Test
    fun defaultClockHonorsTimezoneChangedAfterRepositoryCreation() = runTest {
        val original = TimeZone.getDefault()
        val dynamicRepository = RoutineRepository(database)
        try {
            // The two zones always have different calendar dates, regardless of time of day.
            for (targetZone in listOf(ZoneOffset.ofHours(-12), ZoneOffset.ofHours(14))) {
                val id = save()
                val item = requireNotNull(repository.getItem(id))
                TimeZone.setDefault(TimeZone.getTimeZone(targetZone))
                dynamicRepository.completePurchase(id, item.cycleId)
                val record = requireNotNull(database.purchaseDao().getForCycle(id, item.cycleId))
                val completedDate = Instant.ofEpochMilli(record.completedAtEpochMillis).atZone(targetZone).toLocalDate()
                assertEquals(completedDate.plusDays(7).toEpochDay(), dynamicRepository.getItem(id)?.nextPurchaseEpochDay)
            }
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test
    fun concurrentConfirmationsConsumeExactlyOneCycle() = runTest {
        val id = save()
        val cycle = requireNotNull(repository.getItem(id)).cycleId
        val results = List(24) {
            async(Dispatchers.Default) { repository.completePurchase(id, cycle) }
        }.awaitAll()
        assertEquals(1, results.count { it == CompletionResult.Completed })
        assertEquals(23, results.count { it == CompletionResult.AlreadyRecorded })
        assertEquals(1, repository.history.first().size)
        assertEquals(day("2026-10-29"), repository.getItem(id)?.nextPurchaseEpochDay)
        assertEquals(CompletionResult.AlreadyRecorded, repository.completePurchase(id, cycle))
    }

    @Test
    fun editedCycleRejectsOldConfirmation() = runTest {
        val id = save()
        val before = requireNotNull(repository.getItem(id))
        save(id = id, quantity = 8, due = "2026-12-01")
        assertEquals(CompletionResult.Stale, repository.completePurchase(id, before.cycleId))
        assertTrue(repository.history.first().isEmpty())
        assertEquals(8, repository.getItem(id)?.quantity)
        assertEquals(day("2026-12-01"), repository.getItem(id)?.nextPurchaseEpochDay)
    }

    @Test
    fun dueQueryFiltersFuturePausedAndArchivedItems() = runTest {
        val overdue = save(due = "2026-10-01")
        val today = save(due = "2026-10-22")
        save(due = "2026-10-23")
        val paused = save()
        val archived = save()
        val pausedCycle = requireNotNull(repository.getItem(paused)).cycleId
        val archivedCycle = requireNotNull(repository.getItem(archived)).cycleId
        repository.setEnabled(paused, false)
        repository.archiveItem(archived)
        assertEquals(listOf(overdue, today), repository.getDueItems(day("2026-10-22")).map { it.id })
        assertEquals(CompletionResult.Stale, repository.completePurchase(paused, pausedCycle))
        assertEquals(CompletionResult.Stale, repository.completePurchase(archived, archivedCycle))
        assertFalse(repository.items.first().any { it.id == archived })
        assertTrue(requireNotNull(repository.getItem(archived)).archived)
        repository.setEnabled(paused, true)
        assertNotEquals(pausedCycle, repository.getItem(paused)?.cycleId)
        assertTrue(repository.getDueItems(day("2026-10-22")).any { it.id == paused })
    }

    @Test
    fun historySurvivesLaterEditsAndArchival() = runTest {
        val id = save(title = "이전 이름", quantity = 3)
        val before = requireNotNull(repository.getItem(id))
        repository.completePurchase(id, before.cycleId)
        save(id = id, title = "새 이름", quantity = 9, url = "https://link.coupang.com/a/new")
        repository.archiveItem(id)
        val record = repository.history.first().single()
        assertEquals("이전 이름", record.title)
        assertEquals(3, record.quantity)
        assertEquals(before.productUrl, record.productUrl)
        assertEquals(CompletionResult.AlreadyRecorded, repository.completePurchase(id, before.cycleId))
        assertTrue(repository.items.first().isEmpty())
    }

    @Test
    fun changingNothingAboutEnabledStateDoesNotInvalidateCycle() = runTest {
        val id = save()
        val before = requireNotNull(repository.getItem(id))
        repository.setEnabled(id, true)
        assertEquals(before.cycleId, repository.getItem(id)?.cycleId)
    }

    @Test
    fun missingItemIsReportedWithoutWritingHistory() = runTest {
        assertEquals(CompletionResult.NotFound, repository.completePurchase("missing", "missing-cycle"))
        assertTrue(repository.history.first().isEmpty())
    }

    @Test
    fun rejectsBadInputsBeforeWriting() = runTest {
        val attempts: List<suspend () -> Unit> = listOf(
            { save(title = " ") },
            { save(title = "가".repeat(101)) },
            { save(quantity = 0) },
            { save(quantity = 1000) },
            { save(interval = 0) },
            { save(interval = 366) },
            { save(due = "2101-01-01") },
            { save(url = "https://evil.example/product/1") },
        )
        for (attempt in attempts) {
            try {
                attempt()
                fail("Invalid input was accepted")
            } catch (_: IllegalArgumentException) {
                // Expected validation failure.
            }
        }
        assertTrue(repository.items.first().isEmpty())
    }

    @Test
    fun oneSavePersistsNormalizedOptionAndLimitTogetherWithoutRestrictingRegisteredQuantity() = runTest {
        val id = save(quantity = 999, purchaseOption = " ６００ｍｌ,\u3000２개  ", maxTotalWon = 100_000_000)
        val item = requireNotNull(repository.getItem(id))
        assertEquals("600ml, 2개", item.purchaseOption)
        assertEquals(100_000_000L, item.maxTotalWon)
        assertEquals(999, item.quantity)
        assertEquals(listOf(item), repository.items.first())
        assertTrue(repository.history.first().isEmpty())
    }

    @Test
    fun invalidPurchaseConditionsCannotCreateAnyRow() = runTest {
        val invalid = listOf(
            null to 75_000L,
            " " to 75_000L,
            "가" to 75_000L,
            "!!" to 75_000L,
            "가!" to 75_000L,
            "가".repeat(121) to 75_000L,
            "600ml,2개" to 0L,
            "600ml,2개" to 100_000_001L,
        )
        for ((option, limit) in invalid) {
            try {
                save(purchaseOption = option, maxTotalWon = limit)
                fail("Invalid purchase conditions were accepted")
            } catch (_: IllegalArgumentException) { }
        }
        assertTrue(repository.items.first().isEmpty())
    }

    @Test
    fun invalidConditionEditLeavesEveryPreviouslySavedFieldUnchanged() = runTest {
        val id = save(purchaseOption = "600ml,2개", maxTotalWon = 75_000)
        val before = requireNotNull(repository.getItem(id))
        try {
            save(id = id, title = "바뀌면 안 되는 이름", quantity = 8, purchaseOption = "600ml,3개", maxTotalWon = 0)
            fail("Invalid limit was accepted")
        } catch (_: IllegalArgumentException) { }
        assertEquals(before, repository.getItem(id))
    }

    @Test
    fun legacyEditPreservesRegisteredPurchaseConditionsAndPausedState() = runTest {
        val id = save(purchaseOption = "600ml,2개", maxTotalWon = 75_000)
        repository.setEnabled(id, false)
        val before = requireNotNull(repository.getItem(id))
        save(id = id, title = "수정한 상품명", quantity = 3)
        val after = requireNotNull(repository.getItem(id))
        assertEquals("수정한 상품명", after.title)
        assertEquals(3, after.quantity)
        assertEquals(before.purchaseOption, after.purchaseOption)
        assertEquals(before.maxTotalWon, after.maxTotalWon)
        assertFalse(after.enabled)
        assertNotEquals(before.cycleId, after.cycleId)
    }

    @Test
    fun updatedConditionsReplaceThePairAndInvalidateOldConfirmation() = runTest {
        val id = save(purchaseOption = "600ml,2개", maxTotalWon = 75_000)
        val before = requireNotNull(repository.getItem(id))
        save(id = id, purchaseOption = "600ml,3개", maxTotalWon = 80_000)
        val after = requireNotNull(repository.getItem(id))
        assertEquals("600ml,3개", after.purchaseOption)
        assertEquals(80_000L, after.maxTotalWon)
        assertNotEquals(before.cycleId, after.cycleId)
        assertEquals(CompletionResult.Stale, repository.completePurchase(id, before.cycleId))
        assertTrue(repository.history.first().isEmpty())
    }

    @Test
    fun failedDatabaseWriteCannotPartiallyChangeAnItemOrItsConditions() = runTest {
        val id = save(purchaseOption = "600ml,2개", maxTotalWon = 75_000)
        val before = requireNotNull(repository.getItem(id))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_condition_edit BEFORE UPDATE ON routine_items " +
                "WHEN NEW.maxTotalWon = 99999 BEGIN SELECT RAISE(ABORT, 'test condition rejection'); END",
        )
        val failure = runCatching {
            save(id = id, title = "실패할 수정", purchaseOption = "600ml,3개", maxTotalWon = 99_999)
        }.exceptionOrNull()
        assertNotNull("The fixture constraint must reject the write", failure)
        assertEquals(before, repository.getItem(id))
        assertEquals(listOf(before), repository.items.first())
    }

    private suspend fun save(
        id: String? = null,
        title: String = "생수",
        url: String = "https://www.coupang.com/vp/products/123?itemId=456",
        quantity: Int = 2,
        interval: Int = 7,
        due: String = "2026-10-21",
        purchaseOption: String? = null,
        maxTotalWon: Long? = null,
    ) = repository.saveItem(id, title, url, quantity, interval, day(due), purchaseOption, maxTotalWon)

    private fun day(value: String): Long = LocalDate.parse(value).toEpochDay()
}
