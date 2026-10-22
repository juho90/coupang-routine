package dev.local.rebuy.autobuy

import dev.local.rebuy.cart.CartAssistPolicy

/** Pure validation. Subtotals, old crossed-out prices, and ambiguous quantities never authorize a purchase. */
internal object AutoBuyCheckoutPolicy {
    data class Product(val title: String, val option: String, val quantity: Int)
    data class CheckoutFacts(
        val products: List<Product>,
        val finalTotalCandidates: List<Long>,
        val shippingWon: Long?,
        val hasCheckoutHeader: Boolean,
        val hasPaymentButton: Boolean,
    )

    sealed interface Check {
        data class Ready(val totalWon: Long) : Check
        data class Reject(val reason: String) : Check
    }

    fun inspect(profile: AutoBuyProfile, facts: CheckoutFacts): Check {
        if (!facts.hasCheckoutHeader || !facts.hasPaymentButton) return Check.Reject("주문/결제 화면을 확인하지 못했습니다.")
        if (facts.products.size != 1) return Check.Reject("주문에 정확히 한 상품만 있는지 확인하지 못했습니다.")
        val product = facts.products.single()
        if (CartAssistPolicy.normalize(product.title) != CartAssistPolicy.normalize(profile.expectedTitle) ||
            CartAssistPolicy.normalize(product.option).removePrefix("옵션") != CartAssistPolicy.normalize(profile.expectedOption)) {
            return Check.Reject("주문 상품명 또는 옵션이 등록한 내용과 다릅니다.")
        }
        if (product.quantity != 1 || profile.quantitySnapshot != 1) return Check.Reject("현재 시험은 구매 수량 1개만 지원합니다.")
        if (facts.shippingWon == null || facts.shippingWon < 0) return Check.Reject("배송비를 정확히 확인하지 못했습니다.")
        val totals = facts.finalTotalCandidates.distinct()
        if (totals.size != 1 || totals.single() <= 0) return Check.Reject("최종 결제 금액이 없거나 여러 금액이 함께 표시되어 구분하지 못했습니다.")
        val total = totals.single()
        if (total < facts.shippingWon || profile.maxTotalWon <= 0 || total > profile.maxTotalWon) {
            return Check.Reject("배송비를 포함한 최종 결제 금액이 설정한 한도를 넘거나 올바르지 않습니다.")
        }
        return Check.Ready(total)
    }

    fun parseWon(text: String): Long? {
        val normalized = text.trim().replace(" ", "")
        if (!normalized.matches(Regex("(?:0|[1-9][0-9]{0,2}(?:,[0-9]{3})*|[1-9][0-9]*)원"))) return null
        return normalized.removeSuffix("원").replace(",", "").toLongOrNull()?.takeIf { it <= 100_000_000 }
    }
}
