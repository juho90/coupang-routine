package dev.local.rebuy.importproduct

import dev.local.rebuy.cart.CartAssistPolicy
import dev.local.rebuy.cart.CartNodeTraversal
import java.text.Normalizer

internal object ProductLinkPricePolicy {
    private const val AMOUNT = "(?:[1-9][0-9]{0,2}(?:,[0-9]{3})+|[1-9][0-9]*)원"
    private val anyWon = Regex("[0-9][0-9,]*원")
    private val currentPrice = Regex("^(?:(?:판매가|판매가격|상품가|상품가격|상품금액|가격)[:：]?)?($AMOUNT)$")
    private val oldPrice = Regex("^(?:정가|정상가|원래가|원래가격|기존가)[:：]?$AMOUNT$")
    private val unitPrice = Regex("^\\(?(?:[0-9]+(?:\\.[0-9]+)?)?(?:kg|mg|ml|g|l|ℓ|개|팩|봉|병|세트|매|장|그램|리터)당$AMOUNT\\)?$", RegexOption.IGNORE_CASE)
    private val unitPricePart = Regex("\\(?(?:[0-9]+(?:\\.[0-9]+)?)?(?:kg|mg|ml|g|l|ℓ|개|팩|봉|병|세트|매|장|그램|리터)당$AMOUNT\\)?", RegexOption.IGNORE_CASE)
    private val detachedLabel = Regex("^(?:정가|정상가|원래가|원래가격|기존가|단가|배송비|배송료|배달비|쿠폰가|쿠폰할인액|와우가|와우회원가|회원가|카드할인가|할인가|할인액|할인금액|적립금|캐시적립|총금액|결제금액|\\(?(?:[0-9]+(?:\\.[0-9]+)?)?(?:kg|mg|ml|g|l|ℓ|개|팩|봉|병|세트|매|장|그램|리터)당\\)?)[:：]?$", RegexOption.IGNORE_CASE)
    private val detachedConditionalLabel = Regex("^.*(?:쿠폰|와우|회원|카드|할부|적립|캐시).*(?:가|가격|금액|할부금|할인|적립|원)[:：]?$")
    private val auxiliary = Regex("쿠폰|와우|회원|카드|할부|적립|캐시|할인(?:액|금액|적용|받)|절약|배송비|배송료|배달비|배달료|^총|^결제금액")
    private val excludedSection = Regex("recommend|related|sponsor|advertis", RegexOption.IGNORE_CASE)

    fun read(nodes: List<CartAssistPolicy.Node>, truncated: Boolean): Long? {
        if (truncated || nodes.size > CartNodeTraversal.VISIBLE_SCAN_LIMIT || !CartAssistPolicy.isDetailScreen(nodes)) return null
        val lines = nodes.asSequence().filter { node ->
            node.visible && ProductLinkImportPolicy.inDetailContent(node.ancestorIds) &&
                (node.ancestorIds + node.resourceId).none { excludedSection.containsMatchIn(it) }
        }.map { Normalizer.normalize(it.text, Normalizer.Form.NFKC).replace(Regex("[\\s\\p{Z}]+"), "") }
            .filter(String::isNotBlank).toList()
        if (lines.size > 200 || lines.any { it.length > 4096 } || lines.sumOf { it.length } > 32_000) return null
        if (lines.any { detachedLabel.matches(it) || (!anyWon.containsMatchIn(it) && detachedConditionalLabel.matches(it)) }) return null
        val prices = linkedSetOf<Long>()
        for (line in lines) {
            if (!anyWon.containsMatchIn(line)) continue
            if (oldPrice.matches(line) || unitPrice.matches(line) || auxiliary.containsMatchIn(line)) continue
            val priceText = unitPricePart.replace(line, "")
            val match = currentPrice.matchEntire(priceText) ?: return null
            val amount = match.groupValues[1].removeSuffix("원").replace(",", "").toLongOrNull()
                ?.takeIf { it in 1L..100_000_000L } ?: return null
            prices += amount
        }
        return prices.singleOrNull()
    }
}
