package dev.local.rebuy.autobuy

import dev.local.rebuy.data.RoutineItem
import dev.local.rebuy.domain.CoupangUrl
import java.util.UUID

/** The current build deliberately has no authorization to execute an actual purchase. */
object AutoBuyPolicy {
    const val ACTUAL_PURCHASE_ENABLED = false
    const val PURCHASE_BLOCKED_MESSAGE = "구매 차단 상태입니다. 이 버전은 자동 주문·결제를 실행하지 않습니다."
}

data class AutoBuyProfile(
    val itemId: String,
    val expectedTitle: String,
    val expectedOption: String,
    val maxTotalWon: Long,
    val productUrlSnapshot: String,
    val quantitySnapshot: Int,
    val enabled: Boolean = false,
    val revisionId: String = UUID.randomUUID().toString(),
) {
    fun requireMatches(item: RoutineItem) {
        require(item.id == itemId && !item.archived) { "등록된 상품을 다시 확인해 주세요." }
        require(quantitySnapshot == 1 && item.quantity == quantitySnapshot) {
            "현재 미리보기는 수량 1개만 지원합니다. 상품 수량을 변경한 뒤 설정을 다시 저장해 주세요."
        }
        require(CoupangUrl.normalize(item.productUrl) == productUrlSnapshot) {
            "상품 주소가 변경됐습니다. 주문 조건을 다시 저장해 주세요."
        }
    }
}

enum class AutoBuyStatus {
    CLAIMED, BLOCKED, PREPARING, PAYMENT_SUBMITTED, CONFIRMED, DRY_RUN_READY, FAILED, UNCERTAIN,
}

data class AutoBuyAttempt(
    val id: String = UUID.randomUUID().toString(),
    val itemId: String,
    val cycleId: String,
    val profileRevisionId: String,
    val dryRun: Boolean,
    val status: AutoBuyStatus = AutoBuyStatus.CLAIMED,
    val message: String = "주문 조건 미리보기를 준비하고 있습니다.",
    val orderId: String? = null,
    val totalWon: Long? = null,
    val externalMutationAtEpochMillis: Long? = null,
    val paymentSubmittedAtEpochMillis: Long? = null,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
)

sealed interface AutoBuyOutcome {
    data class Confirmed(val orderId: String, val totalWon: Long) : AutoBuyOutcome
    data class DryRunReady(val totalWon: Long) : AutoBuyOutcome
    data class Blocked(val reason: String) : AutoBuyOutcome
    data class Failed(val reason: String) : AutoBuyOutcome
    data class Uncertain(val reason: String) : AutoBuyOutcome
}
