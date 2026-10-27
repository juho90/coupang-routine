package dev.local.rebuy.cart

import dev.local.rebuy.domain.CoupangCartProductLink
import kotlinx.coroutines.delay

data class CartBatchTarget(val itemId: String, val title: String, val productUrl: String, val option: String, val quantity: Int = 1)
enum class CartBatchEntryStatus { PENDING, RUNNING, ADDED, FAILED, NOT_RUN }
data class CartBatchEntry(val target: CartBatchTarget, val status: CartBatchEntryStatus = CartBatchEntryStatus.PENDING, val message: String = "")
data class CartBatchState(
    val entries: List<CartBatchEntry> = emptyList(),
    val running: Boolean = false,
    val message: String = "",
    val interrupted: Boolean = false,
) {
    val addedCount: Int get() = entries.count { it.status == CartBatchEntryStatus.ADDED }
    val totalCount: Int get() = entries.size
}
internal data class CartBatchAttemptResult(val success: Boolean, val message: String, val verifiedOnly: Boolean = false)

internal object CartBatchValidation {
    const val MAX_TARGETS = 20
    fun prepare(targets: List<CartBatchTarget>): List<CartBatchTarget> {
        require(targets.size in 1..MAX_TARGETS) { "한 번에 등록 상품 1~20개를 담을 수 있습니다." }
        val prepared = targets.map { item ->
            require(item.itemId.isNotBlank() && item.itemId.length <= 100) { "등록 상품 식별자를 확인해 주세요." }
            require(item.quantity == 1) { "여러 상품 담기도 상품별 구매 수량 1개만 지원합니다." }
            require(item.title.length <= 250 && CartAssistPolicy.normalize(item.title).length >= 4) { "상품명을 확인해 주세요." }
            require(item.option.length <= 120 && CartAssistPolicy.normalizeOption(item.option).length >= 2) { "구매 옵션을 확인해 주세요." }
            val product = CoupangCartProductLink.parse(item.productUrl)
            item.copy(title = item.title.trim(), option = item.option.trim(), productUrl = product.canonicalUrl)
        }
        require(prepared.distinctBy { it.itemId }.size == prepared.size) { "동일한 등록 상품이 중복 선택됐습니다." }
        require(prepared.distinctBy { it.productUrl }.size == prepared.size) { "같은 상품 주소가 중복 등록되어 있습니다. 중복을 정리해 주세요." }
        require(prepared.distinctBy { CartAssistPolicy.normalize(it.title) to CartAssistPolicy.normalizeOption(it.option) }.size == prepared.size) {
            "같은 상품명·옵션이 중복되어 구분할 수 없습니다. 중복을 정리해 주세요."
        }
        return prepared.toList()
    }
}

/** Each native operation must finish before the next begins. No retry path exists here. */
internal object CartBatchSequence {
    suspend fun run(
        targets: List<CartBatchTarget>,
        execute: suspend (CartBatchTarget) -> CartBatchAttemptResult,
        publish: (CartBatchState) -> Boolean,
        isCancelled: () -> Boolean,
    ): CartBatchState {
        val prepared = CartBatchValidation.prepare(targets)
        var state = CartBatchState(prepared.map(::CartBatchEntry), running = true)
        fun stop(message: String, index: Int? = null): CartBatchState = state.copy(
            running = false, message = message,
            entries = state.entries.mapIndexed { entryIndex, entry ->
                when {
                    entryIndex == index && entry.status == CartBatchEntryStatus.RUNNING ->
                        entry.copy(status = CartBatchEntryStatus.FAILED, message = message)
                    entry.status == CartBatchEntryStatus.PENDING || entry.status == CartBatchEntryStatus.RUNNING ->
                        entry.copy(status = CartBatchEntryStatus.NOT_RUN, message = "중지되어 실행하지 않았습니다.")
                    else -> entry
                }
            },
        )
        for ((index, target) in prepared.withIndex()) {
            if (isCancelled()) return stop("사용자가 중지했습니다. 완료된 상품은 다시 담지 않습니다.")
            state = state.copy(
                entries = state.entries.mapIndexed { entryIndex, entry ->
                    if (entryIndex == index) entry.copy(status = CartBatchEntryStatus.RUNNING, message = "쿠팡에서 확인 중입니다.") else entry
                },
                message = (index + 1).toString() + "/" + prepared.size + " 상품을 확인하고 있습니다.",
            )
            if (!publish(state)) return stop("진행 기록을 저장하지 못해 다음 동작을 시작하지 않았습니다.", index)
            if (isCancelled()) return stop("사용자가 중지했습니다. 현재 상품의 결과는 장바구니에서 확인해 주세요.", index)
            val result = execute(target)
            if (!result.success || result.verifiedOnly || isCancelled()) {
                val reason = when {
                    isCancelled() -> "사용자가 중지했습니다. 현재 상품의 결과는 장바구니에서 확인해 주세요."
                    result.verifiedOnly -> "담기 전 점검은 실제 담기 완료로 처리할 수 없습니다."
                    else -> result.message
                }
                return stop(reason + " 다음 상품은 시작하지 않았습니다.", index)
            }
            state = state.copy(entries = state.entries.mapIndexed { entryIndex, entry ->
                if (entryIndex == index) entry.copy(status = CartBatchEntryStatus.ADDED, message = result.message) else entry
            })
            if (!publish(state)) return stop("완료 기록 저장에 실패해 나머지 상품은 시작하지 않았습니다.")
            if (index < prepared.lastIndex) delay(500) // Let the previous native scan/runnable unwind.
        }
        return state.copy(running = false, message = state.addedCount.toString() + "개 상품의 담기 완료 안내를 확인했습니다.")
    }

    fun interrupted(state: CartBatchState): CartBatchState = state.copy(
        running = false, interrupted = true,
        message = "이전 실행이 중단됐습니다. 장바구니에서 결과를 확인해 주세요. 자동으로 다시 담지 않습니다.",
        entries = state.entries.map { entry ->
            when (entry.status) {
                CartBatchEntryStatus.RUNNING -> entry.copy(status = CartBatchEntryStatus.FAILED, message = "실행 결과 확인 필요")
                CartBatchEntryStatus.PENDING -> entry.copy(status = CartBatchEntryStatus.NOT_RUN, message = "중단되어 실행하지 않았습니다.")
                else -> entry
            }
        },
    )
}
