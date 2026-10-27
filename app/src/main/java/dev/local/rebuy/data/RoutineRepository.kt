package dev.local.rebuy.data

import androidx.room.withTransaction
import dev.local.rebuy.domain.CompletionResult
import dev.local.rebuy.domain.CoupangUrl
import dev.local.rebuy.domain.PurchaseSchedule
import dev.local.rebuy.domain.CoupangCartProductLink
import dev.local.rebuy.domain.ProductVariantCodec
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.text.Normalizer
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
        purchaseOption: String? = null,
        maxTotalWon: Long? = null,
        productVariantsJson: String? = null,
        clearMaxTotalWon: Boolean = false,
    ): String {
        require(!clearMaxTotalWon || maxTotalWon == null) { "금액 한도 삭제와 새 한도 저장은 동시에 요청할 수 없습니다." }
        val cleanTitle = title.trim()
        require(cleanTitle.length in 1..100) { "상품명은 1~100자로 입력해 주세요." }
        require(quantity in 1..999) { "수량은 1~999개로 입력해 주세요." }
        require(intervalDays in 1..365) { "구매 주기는 1~365일로 입력해 주세요." }
        PurchaseSchedule.requireValidDate(nextPurchaseEpochDay)
        val cleanUrl = CoupangUrl.normalize(productUrl)
        require(maxTotalWon == null || purchaseOption != null) {
            "금액 한도를 저장하려면 구매 옵션도 입력해 주세요."
        }
        val cleanOption = purchaseOption?.let { raw ->
            Normalizer.normalize(raw, Normalizer.Form.NFKC).replace(Regex("[\\s\\p{Z}]+"), " ").trim().also {
                require(it.length in 2..120) { "구매 옵션은 2~120자로 입력해 주세요." }
                require(it.count(Char::isLetterOrDigit) >= 2) { "구매 옵션에 글자나 숫자를 2자 이상 입력해 주세요." }
            }
        }
        if (maxTotalWon != null) require(maxTotalWon in 1..100_000_000) {
            "총 결제 한도는 1원~1억원으로 입력해 주세요."
        }
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
                    purchaseOption = cleanOption,
                    maxTotalWon = maxTotalWon,
                    productVariantsJson = productVariantsJson?.let { checkedVariantSelection(it, cleanTitle, cleanUrl, cleanOption) },
                )
                routineDao.insert(item)
                item.id
            } else {
                val item = requireNotNull(routineDao.getItem(id)) { "등록된 상품을 찾을 수 없어요." }
                require(!item.archived) { "삭제된 상품은 수정할 수 없어요." }
                val nextOption = cleanOption ?: item.purchaseOption
                val nextVariants = if (productVariantsJson != null) {
                    checkedVariantSelection(productVariantsJson, cleanTitle, cleanUrl, nextOption)
                } else item.productVariantsJson?.takeIf {
                    sameSelection(item.title, item.productUrl, item.purchaseOption, cleanTitle, cleanUrl, nextOption)
                }
                routineDao.update(
                    item.copy(
                        title = cleanTitle,
                        productUrl = cleanUrl,
                        quantity = quantity,
                        intervalDays = intervalDays,
                        nextPurchaseEpochDay = nextPurchaseEpochDay,
                        cycleId = UUID.randomUUID().toString(),
                        updatedAtEpochMillis = now,
                        // Older callers omit both arguments; preserve previously registered conditions.
                        purchaseOption = nextOption,
                        maxTotalWon = if (clearMaxTotalWon) null else maxTotalWon ?: item.maxTotalWon,
                        productVariantsJson = nextVariants,
                    ),
                )
                id
            }
        }
    }

    /** Selection is resolved from saved choices, never from a caller-supplied label or URL alone. */
    suspend fun selectVariant(itemId: String, canonicalUrl: String) = database.withTransaction {
        val item = requireNotNull(routineDao.getItem(itemId)) { "등록된 상품을 찾을 수 없어요." }
        require(!item.archived) { "보관한 상품의 옵션은 바꿀 수 없어요." }
        val address = CoupangCartProductLink.parse(canonicalUrl).canonicalUrl
        val variant = ProductVariantCodec.decodeStrict(item.productVariantsJson ?: "[]")
            .singleOrNull { it.productUrl == address }
            ?: throw IllegalArgumentException("저장된 옵션 목록에 없는 상품 주소입니다.")
        if (sameSelection(item.title, item.productUrl, item.purchaseOption, variant.title, variant.productUrl, variant.option)) {
            return@withTransaction
        }
        routineDao.update(item.copy(
            title = variant.title, productUrl = variant.productUrl, purchaseOption = variant.option,
            cycleId = UUID.randomUUID().toString(), updatedAtEpochMillis = effectiveClock().millis(),
        ))
    }

    private fun checkedVariantSelection(raw: String, title: String, url: String, option: String?): String? {
        val variants = ProductVariantCodec.decodeStrict(raw)
        if (variants.isEmpty()) return null // An explicit empty list clears saved choices.
        require(variants.any { sameSelection(it.title, it.productUrl, it.option, title, url, option) }) {
            "현재 상품명·주소·구매 옵션이 저장할 옵션 목록과 일치하지 않습니다."
        }
        return ProductVariantCodec.encode(variants)
    }

    private fun sameSelection(
        firstTitle: String, firstUrl: String, firstOption: String?,
        secondTitle: String, secondUrl: String, secondOption: String?,
    ): Boolean {
        fun address(raw: String) = runCatching { CoupangCartProductLink.parse(raw).canonicalUrl }.getOrDefault(raw)
        return firstTitle == secondTitle && firstOption == secondOption && address(firstUrl) == address(secondUrl)
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
