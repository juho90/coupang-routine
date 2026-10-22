package dev.local.rebuy.data

import androidx.room.withTransaction
import dev.local.rebuy.domain.CompletionResult
import dev.local.rebuy.domain.CoupangUrl
import dev.local.rebuy.domain.PurchaseSchedule
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

class RoutineRepository(
    private val database: AppDatabase,
    private val clock: Clock? = null,
) {
    private val routineDao = database.routineDao()
    private val purchaseDao = database.purchaseDao()

    val items: Flow<List<RoutineItem>> = routineDao.observeItems()
    val history: Flow<List<PurchaseRecord>> = purchaseDao.observeHistory()

    // Resolve the default zone per operation so a timezone change in a running process is honored.
    // Injected clocks keep their explicit zone for deterministic tests.
    private fun effectiveClock(): Clock = clock ?: Clock.systemDefaultZone()

    suspend fun getItem(id: String): RoutineItem? = routineDao.getItem(id)

    suspend fun getDueItems(todayEpochDay: Long): List<RoutineItem> = routineDao.getDueItems(todayEpochDay)

    suspend fun saveItem(
        id: String?,
        title: String,
        productUrl: String,
        quantity: Int,
        intervalDays: Int,
        nextPurchaseEpochDay: Long,
    ): String {
        val cleanTitle = title.trim()
        require(cleanTitle.length in 1..100) { "상품명은 1~100자로 입력해 주세요." }
        require(quantity in 1..999) { "수량은 1~999개로 입력해 주세요." }
        require(intervalDays in 1..365) { "구매 주기는 1~365일로 입력해 주세요." }
        PurchaseSchedule.requireValidDate(nextPurchaseEpochDay)
        val cleanUrl = CoupangUrl.normalize(productUrl)
        return database.withTransaction {
            val now = effectiveClock().millis()
            if (id == null) {
                val item = RoutineItem(
                    title = cleanTitle,
                    productUrl = cleanUrl,
                    quantity = quantity,
                    intervalDays = intervalDays,
                    nextPurchaseEpochDay = nextPurchaseEpochDay,
                    createdAtEpochMillis = now,
                    updatedAtEpochMillis = now,
                )
                routineDao.insert(item)
                item.id
            } else {
                val item = requireNotNull(routineDao.getItem(id)) { "등록된 상품을 찾을 수 없어요." }
                require(!item.archived) { "삭제된 상품은 수정할 수 없어요." }
                routineDao.update(
                    item.copy(
                        title = cleanTitle,
                        productUrl = cleanUrl,
                        quantity = quantity,
                        intervalDays = intervalDays,
                        nextPurchaseEpochDay = nextPurchaseEpochDay,
                        cycleId = UUID.randomUUID().toString(),
                        updatedAtEpochMillis = now,
                    ),
                )
                id
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) = database.withTransaction {
        val item = routineDao.getItem(id) ?: return@withTransaction
        if (item.archived || item.enabled == enabled) return@withTransaction
        routineDao.update(
            item.copy(
                enabled = enabled,
                cycleId = UUID.randomUUID().toString(),
                updatedAtEpochMillis = effectiveClock().millis(),
            ),
        )
    }

    suspend fun archiveItem(id: String) = database.withTransaction {
        val item = routineDao.getItem(id) ?: return@withTransaction
        if (item.archived) return@withTransaction
        routineDao.update(
            item.copy(
                archived = true,
                enabled = false,
                cycleId = UUID.randomUUID().toString(),
                updatedAtEpochMillis = effectiveClock().millis(),
            ),
        )
    }

    /**
     * A whole cycle is consumed atomically. Replayed notifications or concurrent taps cannot
     * create two records or advance the next date twice. An edited cycle cannot be confirmed.
     */
    suspend fun completePurchase(itemId: String, expectedCycleId: String): CompletionResult =
        database.withTransaction {
            if (purchaseDao.getForCycle(itemId, expectedCycleId) != null) {
                return@withTransaction CompletionResult.AlreadyRecorded
            }
            val item = routineDao.getItem(itemId) ?: return@withTransaction CompletionResult.NotFound
            if (item.archived || !item.enabled || item.cycleId != expectedCycleId) {
                return@withTransaction CompletionResult.Stale
            }
            val completionClock = effectiveClock()
            val now = completionClock.millis()
            val inserted = purchaseDao.insertOnce(
                PurchaseRecord(
                    itemId = item.id,
                    cycleId = expectedCycleId,
                    title = item.title,
                    productUrl = item.productUrl,
                    quantity = item.quantity,
                    scheduledEpochDay = item.nextPurchaseEpochDay,
                    completedAtEpochMillis = now,
                ),
            )
            if (inserted == -1L) return@withTransaction CompletionResult.AlreadyRecorded
            routineDao.update(
                item.copy(
                    nextPurchaseEpochDay = PurchaseSchedule.nextPurchaseEpochDay(
                        Instant.ofEpochMilli(now).atZone(completionClock.zone).toLocalDate().toEpochDay(),
                        item.intervalDays,
                    ),
                    cycleId = UUID.randomUUID().toString(),
                    updatedAtEpochMillis = now,
                ),
            )
            CompletionResult.Completed
        }
}
