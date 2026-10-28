package dev.local.rebuy.cart

import java.text.Normalizer
import java.text.NumberFormat
import java.util.Locale

internal object CartPricePolicy {
    sealed interface Result {
        data object Unrestricted : Result
        data class Allowed(val priceWon: Long) : Result
        data class Blocked(val message: String) : Result
    }

    private const val MONEY = "(?:[1-9][0-9]{0,2}(?:,[0-9]{3})+|[1-9][0-9]*)원"
    private val anyMoney = Regex("[0-9][0-9,]*원")
    private val sellingPrice = Regex("^(?:(?:판매가|상품금액|상품가격|판매가격|가격)[:：]?)?($MONEY)$")
    private val oldPrice = Regex("^(?:정가|정상가|원래가격|기존가)[:：]?$MONEY$")
    private val deliveryPrice = Regex("^(?:배송비|배송료)[:：]?(?:0원|$MONEY)$")
    private val unitPrice = Regex("^\\(?(?:[0-9]+(?:\\.[0-9]+)?(?:kg|mg|ml|g|l|개|팩|봉|병|세트))당$MONEY\\)?$", RegexOption.IGNORE_CASE)
    private val detachedNonPriceLabel = Regex("^(?:정가|정상가|원래가격|기존가|배송비|배송료|\\(?(?:[0-9]+(?:\\.[0-9]+)?)?(?:kg|mg|ml|g|l|개|팩|봉|병|세트|매|장|그램|리터)당\\)?)[:：]?$", RegexOption.IGNORE_CASE)
    private val conditionalPrice = Regex("와우|회원|쿠폰|카드|할부|첫구매|캐시|적립|할인적용|할인받")
    private val excludedSection = Regex("recommend|related|sponsor|advertis", RegexOption.IGNORE_CASE)

    /** Call only after title/option verification, using a complete current detail scan. */
    fun check(nodes: List<CartAssistPolicy.Node>, maxTotalWon: Long?): Result {
        if (maxTotalWon == null) return Result.Unrestricted
        if (maxTotalWon !in 1..100_000_000L) return Result.Blocked("상품가 한도를 확인해 주세요.")
        if (!CartAssistPolicy.isDetailScreen(nodes)) return Result.Blocked("상품 상세 화면을 확인하지 못해 가격을 비교하지 않았습니다.")
        val content = nodes.filter { node ->
            node.visible && node.ancestorIds.any { it in CartAssistPolicy.DETAIL_CONTENT_IDS } &&
                (node.ancestorIds + node.resourceId).none { excludedSection.containsMatchIn(it) }
        }.map { compact(it.text) }.filter(String::isNotBlank)
        if (content.any { detachedNonPriceLabel.matches(it) }) {
            return Result.Blocked("단가·정가·배송비 표기가 분리되어 상품가를 확정하지 못했습니다. 담지 않았습니다.")
        }
        if (content.any { conditionalPrice.containsMatchIn(it) }) {
            return Result.Blocked("쿠폰·회원 등 조건부 가격이 함께 표시되어 상품가를 확정하지 못했습니다. 담지 않았습니다.")
        }
        val prices = linkedSetOf<Long>()
        for (text in content) {
            if (!anyMoney.containsMatchIn(text)) continue
            if (oldPrice.matches(text) || deliveryPrice.matches(text) || unitPrice.matches(text)) continue
            val match = sellingPrice.matchEntire(text)
                ?: return Result.Blocked("상품가와 다른 금액을 구분하지 못했습니다. 담지 않았습니다.")
            val amount = match.groupValues[1].removeSuffix("원").replace(",", "").toLongOrNull()
                ?.takeIf { it in 1..100_000_000L }
                ?: return Result.Blocked("표시 상품가를 정확히 읽지 못했습니다. 담지 않았습니다.")
            prices += amount
        }
        if (prices.size != 1) return Result.Blocked(if (prices.isEmpty())
            "상품가를 읽지 못했습니다. 담지 않았습니다."
            else "상품가가 여러 금액으로 표시되어 하나로 확정하지 못했습니다. 담지 않았습니다.")
        val price = prices.single()
        return if (price > maxTotalWon) {
            val format = NumberFormat.getIntegerInstance(Locale.KOREA)
            Result.Blocked("표시 상품가 ${format.format(price)}원이 등록 한도 ${format.format(maxTotalWon)}원을 넘었습니다. 담지 않았습니다.")
        } else Result.Allowed(price)
    }

    private fun compact(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("[\\s\\u00a0]+"), "")
}
