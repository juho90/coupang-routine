package dev.local.rebuy.importproduct

import dev.local.rebuy.cart.CartAssistPolicy
import org.junit.Assert.*
import org.junit.Test

class ProductLinkPricePolicyTest {
    private val content = listOf(CartAssistPolicy.PREFIX + "web_layout")
    private fun detail(vararg lines: String) = listOf(
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.BOTTOM_BUTTONS_ID),
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.BUTTON_LAYOUT_ID),
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.PREFIX + "web_layout"),
        CartAssistPolicy.Node(text = "장바구니 담기", resourceId = CartAssistPolicy.CART_TEXT_ID, ancestorIds = listOf(CartAssistPolicy.BOTTOM_BUTTONS_ID)),
        CartAssistPolicy.Node(text = "바로구매", resourceId = CartAssistPolicy.CART_TEXT_ID, ancestorIds = listOf(CartAssistPolicy.BOTTOM_BUTTONS_ID)),
    ) + lines.map { CartAssistPolicy.Node(text = it, ancestorIds = content) }

    @Test fun uniqueNeutralPriceIsSuggestedButDuplicateLabelsAreNotMultiplePrices() {
        assertEquals(3890L, ProductLinkPricePolicy.read(detail("3,890원"), false))
        assertEquals(26980L, ProductLinkPricePolicy.read(detail("판매가 : ２６，９８０ 원", "26,980원"), false))
    }

    @Test fun explicitAuxiliaryAmountsNeverFillTheLimit() {
        assertEquals(26980L, ProductLinkPricePolicy.read(detail("26,980원", "정가 35,800원", "100g당 9,467원", "배송비 3,000원", "300원 캐시 적립", "쿠폰가 24,000원"), false))
        for (line in listOf("정가 35,800원", "100g당 9,467원", "배송비 3,000원", "300원 캐시 적립", "쿠폰가 24,000원", "와우가 22,000원", "총금액 29,980원")) {
            assertNull(ProductLinkPricePolicy.read(detail(line), false))
        }
    }

    @Test fun discountNoticesWithoutMoneyDoNotEraseASeparateNeutralPrice() {
        assertEquals(26980L, ProductLinkPricePolicy.read(detail("26,980원", "쿠폰 받기", "캐시 적립 혜택"), false))
    }

    @Test fun mixedOrDifferentPricesRemainUnknownInsteadOfSelectingMinimum() {
        assertNull(ProductLinkPricePolicy.read(detail("26,980원 (100g당 9,467원) 35,800원"), false))
        assertNull(ProductLinkPricePolicy.read(detail("26,980원", "35,800원"), false))
        assertNull(ProductLinkPricePolicy.read(detail("3,000원 할인 적용됨 29,980원"), false))
    }

    @Test fun detachedLabelsCannotUpgradeAnAuxiliaryAmountIntoTheProductPrice() {
        for (label in listOf("정가", "100g당", "개당", "배송비", "와우회원가", "쿠폰가", "할인액", "총금액", "쿠폰 적용가", "쿠폰 적용 가격", "와우 회원 전용가", "카드 할인")) {
            assertNull(ProductLinkPricePolicy.read(detail(label, "3,000원"), false))
        }
    }

    @Test fun truncatedWrongAndMissingScreensCannotSupplyPrice() {
        assertNull(ProductLinkPricePolicy.read(detail("3,890원"), true))
        assertNull(ProductLinkPricePolicy.read(detail("3,890원").filterNot { it.resourceId == CartAssistPolicy.BOTTOM_BUTTONS_ID }, false))
        assertNull(ProductLinkPricePolicy.read(detail(), false))
    }

    @Test fun hiddenRecommendationsAndOutsideTextDoNotContaminateThePrice() {
        val ignored = listOf(
            CartAssistPolicy.Node(text = "1,000원", ancestorIds = content, visible = false),
            CartAssistPolicy.Node(text = "2,000원", ancestorIds = content + "recommendation_list"),
            CartAssistPolicy.Node(text = "3,000원", ancestorIds = content + "related_products"),
            CartAssistPolicy.Node(text = "4,000원"),
        )
        assertEquals(3890L, ProductLinkPricePolicy.read(detail("3,890원") + ignored, false))
        assertNull(ProductLinkPricePolicy.read(detail() + ignored, false))
    }

    @Test fun malformedMissingCurrencyAndOutOfRangeAmountsAreNotRepaired() {
        for (line in listOf("3,89원", "-100원", "0원", "100,000,001원", "999999999999999999999999원", "3,890", "최저가 3,890원")) {
            assertNull(ProductLinkPricePolicy.read(detail(line), false))
        }
    }
    @Test fun textLimitsDoNotSilentlyHideAnotherAmount() {
        assertNull(ProductLinkPricePolicy.read(detail("3,890원", *(Array(200) { "설명" }), "7,000원"), false))
        assertNull(ProductLinkPricePolicy.read(detail("3,890원", "설명".repeat(4097)), false))
    }

    @Test fun inlineExplicitUnitPriceDoesNotReplaceTheProductPrice() {
        assertEquals(3990L, ProductLinkPricePolicy.read(detail("3,990원 (100g당 499원)"), false))
        assertEquals(26980L, ProductLinkPricePolicy.read(detail("판매가 26,980원 (100g당 9,467원)"), false))
        assertNull(ProductLinkPricePolicy.read(detail("26,980원 (100g당 9,467원) 35,800원"), false))
        assertNull(ProductLinkPricePolicy.read(detail("100g당 499원"), false))
    }

}
