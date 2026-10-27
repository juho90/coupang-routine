package dev.local.rebuy.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.local.rebuy.domain.CompletionResult
import dev.local.rebuy.domain.ProductVariant
import dev.local.rebuy.domain.ProductVariantCodec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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
class ProductVariantRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoutineRepository
    private val first = ProductVariant("확인용 두유, 600ml, 1개", "600ml, 1개", "https://www.coupang.com/vp/products/123?itemId=10&vendorItemId=100")
    private val second = ProductVariant("확인용 두유, 900ml, 1개", "900ml, 1개", "https://www.coupang.com/vp/products/123?itemId=20&vendorItemId=200")
    private val choices get() = ProductVariantCodec.encode(listOf(first, second))

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = RoutineRepository(database, Clock.fixed(Instant.parse("2026-10-27T00:00:00Z"), ZoneOffset.UTC))
    }

    @After fun tearDown() = database.close()

    @Test fun selectedOptionMovesNameAddressAndOptionTogetherWithoutChangingScheduleOrLimit() = runTest {
        val id = repository.saveItem(null, first.title, first.productUrl, 3, 7, 20_700, first.option, 90_000, choices)
        repository.setEnabled(id, false)
        val before = requireNotNull(repository.getItem(id))
        repository.selectVariant(id, second.productUrl)
        val after = requireNotNull(repository.getItem(id))
        assertEquals(second.title, after.title)
        assertEquals(second.productUrl, after.productUrl)
        assertEquals(second.option, after.purchaseOption)
        assertEquals(before.productVariantsJson, after.productVariantsJson)
        assertEquals(before.maxTotalWon, after.maxTotalWon)
        assertEquals(before.quantity, after.quantity)
        assertEquals(before.intervalDays, after.intervalDays)
        assertEquals(before.nextPurchaseEpochDay, after.nextPurchaseEpochDay)
        assertEquals(before.enabled, after.enabled)
        assertEquals(before.createdAtEpochMillis, after.createdAtEpochMillis)
        assertNotEquals(before.cycleId, after.cycleId)
        assertTrue(repository.history.first().isEmpty())
        repository.selectVariant(id, second.productUrl)
        assertEquals(after.cycleId, repository.getItem(id)?.cycleId)
        assertEquals(CompletionResult.Stale, repository.completePurchase(id, before.cycleId))
    }

    @Test fun urlImportedCardCanKeepChoicesWithoutInventingAPriceLimit() = runTest {
        val id = repository.saveItem(null, first.title, first.productUrl, 1, 30, 20_700, first.option, null, choices)
        val item = requireNotNull(repository.getItem(id))
        assertNull(item.maxTotalWon)
        assertEquals(listOf(first, second), ProductVariantCodec.decode(item.productVariantsJson))
        repository.selectVariant(id, second.productUrl)
        assertNull(repository.getItem(id)?.maxTotalWon)
        repository.saveItem(id, second.title, second.productUrl, 1, 30, 20_700, second.option, 45_000, choices)
        assertEquals(45_000L, repository.getItem(id)?.maxTotalWon)
        repository.saveItem(id, second.title, second.productUrl, 1, 30, 20_700, second.option, null, choices)
        assertNull(repository.getItem(id)?.maxTotalWon)
        assertEquals(listOf(first, second), ProductVariantCodec.decode(repository.getItem(id)?.productVariantsJson))
    }

    @Test fun unknownAddressOrMismatchedSavedSelectionCannotPartiallyChangeTheCard() = runTest {
        val id = repository.saveItem(null, first.title, first.productUrl, 1, 30, 20_700, first.option, null, choices)
        val before = requireNotNull(repository.getItem(id))
        assertTrue(runCatching { repository.selectVariant(id, "https://www.coupang.com/vp/products/999?itemId=20&vendorItemId=200") }.isFailure)
        assertEquals(before, repository.getItem(id))
        assertTrue(runCatching {
            repository.saveItem(id, "임의 상품명", first.productUrl, 2, 8, 20_710, first.option, null, choices)
        }.isFailure)
        assertEquals(before, repository.getItem(id))
        assertTrue(repository.history.first().isEmpty())
    }

    @Test fun fullSaveRetainsOnlyExplicitlySuppliedChoices() = runTest {
        val id = repository.saveItem(null, first.title, first.productUrl, 1, 30, 20_700, first.option, null, choices)
        repository.saveItem(id, first.title, first.productUrl, 2, 7, 20_710, first.option, null, choices)
        assertEquals(listOf(first, second), ProductVariantCodec.decode(repository.getItem(id)?.productVariantsJson))
        repository.saveItem(id, "다르게 수정한 상품명", first.productUrl, 2, 7, 20_710, first.option, null, null)
        assertNull(repository.getItem(id)?.productVariantsJson)
    }

    @Test fun explicitNullsClearPriorConditionsAndChoicesWithoutReconstructingThem() = runTest {
        val id = repository.saveItem(null, first.title, first.productUrl, 1, 30, 20_700, first.option, 45_000, choices)
        repository.setEnabled(id, false)
        val before = requireNotNull(repository.getItem(id))
        repository.saveItem(id, before.title, before.productUrl, before.quantity, before.intervalDays,
            before.nextPurchaseEpochDay, purchaseOption = null, maxTotalWon = null, productVariantsJson = null)
        val after = requireNotNull(repository.getItem(id))
        assertNull(after.purchaseOption)
        assertNull(after.maxTotalWon)
        assertNull(after.productVariantsJson)
        assertFalse(after.enabled)
        assertEquals(before.createdAtEpochMillis, after.createdAtEpochMillis)
        assertEquals(before.nextPurchaseEpochDay, after.nextPurchaseEpochDay)
        assertNotEquals(before.cycleId, after.cycleId)
        assertTrue(repository.history.first().isEmpty())
    }

    @Test fun choicesRejectOtherProductsDuplicateAddressesAndMalformedJson() {
        assertTrue(runCatching {
            ProductVariantCodec.encode(listOf(first, second.copy(productUrl = "https://www.coupang.com/vp/products/999")))
        }.isFailure)
        assertTrue(runCatching {
            ProductVariantCodec.encode(listOf(first, first.copy(productUrl = first.productUrl + "&sourceType=test")))
        }.isFailure)
        assertTrue(runCatching { ProductVariantCodec.decodeStrict("{broken") }.isFailure)
        assertTrue(ProductVariantCodec.decode("{broken").isEmpty())
    }
}
