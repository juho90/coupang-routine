package dev.local.rebuy.cart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CartPricePolicyTest {
    private val content = listOf(CartAssistPolicy.PREFIX + "web_layout")
    private fun detail(vararg prices: String): List<CartAssistPolicy.Node> = listOf(
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.BOTTOM_BUTTONS_ID),
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.BUTTON_LAYOUT_ID),
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.PREFIX + "web_layout"),
        CartAssistPolicy.Node(text = "장바구니 담기", resourceId = CartAssistPolicy.CART_TEXT_ID,
            ancestorIds = listOf(CartAssistPolicy.BOTTOM_BUTTONS_ID)),
        CartAssistPolicy.Node(text = "바로구매", resourceId = CartAssistPolicy.CART_TEXT_ID,
            ancestorIds = listOf(CartAssistPolicy.BOTTOM_BUTTONS_ID)),
    ) + prices.map { CartAssistPolicy.Node(text = it, ancestorIds = content) }

    private fun blocked(nodes: List<CartAssistPolicy.Node>, limit: Long = 50_000) =
        assertTrue(CartPricePolicy.check(nodes, limit) is CartPricePolicy.Result.Blocked)

    @Test fun noLimitDoesNotCreateAPriceRequirement() {
        assertEquals(CartPricePolicy.Result.Unrestricted, CartPricePolicy.check(emptyList(), null))
    }

    @Test fun exactCurrentPriceAtTheLimitPassesAndOneWonAboveStops() {
        assertEquals(CartPricePolicy.Result.Allowed(3_890), CartPricePolicy.check(detail("3,890원"), 3_890))
        val result = CartPricePolicy.check(detail("3,890원"), 3_889) as CartPricePolicy.Result.Blocked
        assertTrue(result.message.contains("3,890원"))
        assertTrue(result.message.contains("3,889원"))
    }

    @Test fun inlineUnitPriceDoesNotReplaceTheProductPriceOrBypassOtherGuards() {
        assertEquals(CartPricePolicy.Result.Allowed(3_990), CartPricePolicy.check(detail("3,990원 (100g당 499원)"), 3_990))
        blocked(detail("3,990원 (100g당 499원)"), 3_989)
        blocked(detail("3,990원 (100g당 499원) 5,000원"), 5_000)
        blocked(detail("3,990원 (100g당 499원)", "쿠폰 적용가"), 3_990)
    }

    @Test fun labelledSellingPriceAndUnicodeSpacingAreRecognized() {
        assertEquals(CartPricePolicy.Result.Allowed(26_980), CartPricePolicy.check(detail("판매가 : ２６，９８０ 원"), 26_980))
    }

    @Test fun duplicateAccessibilityLabelsAreOnePriceButDistinctAmountsNeverChooseTheCheapest() {
        assertEquals(CartPricePolicy.Result.Allowed(26_980), CartPricePolicy.check(detail("26,980원", "26,980원"), 26_980))
        blocked(detail("26,980원", "35,800원"), 30_000)
    }

    @Test fun explicitOldPriceDeliveryAndUnitPricesDoNotBecomeSellingPrices() {
        assertEquals(CartPricePolicy.Result.Allowed(26_980), CartPricePolicy.check(
            detail("26,980원", "정가 35,800원", "(100g당 9,467원)", "배송비 3,000원"), 26_980))
        blocked(detail("정가 35,800원", "100g당 9,467원", "배송비 3,000원"))
    }

    @Test fun observedMixedPriceAndDiscountStringsDoNotAuthorizeAddition() {
        blocked(detail("26,980원 (100g당 9,467원) 35,800원"))
        blocked(detail("26,980원", "3,000원 할인 적용됨 29,980원"))
    }

    @Test fun detachedUnitOldAndDeliveryLabelsCannotTurnAuxiliaryAmountsIntoSellingPrices() {
        listOf("100g당", "개당", "정가", "배송비").forEach { label ->
            blocked(detail(label, "3,000원"))
        }
    }

    @Test fun conditionalPriceLabelsCannotBeSeparatedFromTheirAmountsToPass() {
        listOf("와우회원가", "쿠폰 적용가", "카드 할인", "월 할부", "첫구매 혜택").forEach { label ->
            blocked(detail(label, "3,000원"))
            blocked(detail("판매가 3,000원", "$label 2,500원"))
        }
    }

    @Test fun hiddenRecommendationsAndOutsideContentDoNotSupplyPriceEvidence() {
        val ignored = listOf(
            CartAssistPolicy.Node(text = "1,000원", visible = false, ancestorIds = content),
            CartAssistPolicy.Node(text = "1,000원", ancestorIds = content + "recommendation_list"),
            CartAssistPolicy.Node(text = "1,000원", ancestorIds = emptyList()),
        )
        assertEquals(CartPricePolicy.Result.Allowed(3_890), CartPricePolicy.check(detail("3,890원") + ignored, 3_890))
        blocked(detail() + ignored)
    }

    @Test fun wrongScreenOrUnknownPriceFormatBlocksRatherThanUsingALowerAmount() {
        blocked(detail("3,890원").filterNot { it.resourceId == CartAssistPolicy.BOTTOM_BUTTONS_ID })
        listOf("26,98원", "-100원", "0원", "100,000,001원", "9999999999999999999999999999999999원", "최저가 3,000원").forEach {
            blocked(detail(it))
        }
        blocked(detail("상품금액 확인 필요"))
    }

    @Test fun invalidLimitDoesNotDisableTheGuard() {
        blocked(detail("3,890원"), 0)
        blocked(detail("3,890원"), -1)
        blocked(detail("3,890원"), 100_000_001)
    }
}
