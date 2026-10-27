package dev.local.rebuy.importproduct

import dev.local.rebuy.cart.CartAssistPolicy
import org.junit.Assert.*
import org.junit.Test

class ProductLinkImportPolicyTest {
    private fun detail() = listOf(
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.BOTTOM_BUTTONS_ID),
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.BUTTON_LAYOUT_ID),
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.PREFIX + "recycler_view"),
        CartAssistPolicy.Node(text = "장바구니 담기", resourceId = CartAssistPolicy.CART_TEXT_ID, ancestorIds = listOf(CartAssistPolicy.BOTTOM_BUTTONS_ID)),
        CartAssistPolicy.Node(text = "바로구매", resourceId = CartAssistPolicy.CART_TEXT_ID, ancestorIds = listOf(CartAssistPolicy.BOTTOM_BUTTONS_ID)),
    )

    @Test fun suggestionsRequireProductDetailAndExcludeRecommendationAndOutsideText() {
        val content = listOf(CartAssistPolicy.PREFIX + "recycler_view")
        val nodes = detail() + listOf(
            CartAssistPolicy.Node(text = "돌 스위티오 바나나, 1kg 내외, 1개", ancestorIds = content),
            CartAssistPolicy.Node(text = "추천 다른상품, 10kg, 5개", ancestorIds = content + (CartAssistPolicy.PREFIX + "recommendation_layout")),
            CartAssistPolicy.Node(text = "외부 상품, 20kg, 3개"),
            CartAssistPolicy.Node(text = "숨긴 상품, 30kg, 2개", ancestorIds = content, visible = false),
            CartAssistPolicy.Node(text = "한 달간 30만명 이상 구매했어요", ancestorIds = content),
            CartAssistPolicy.Node(text = "내일(수) 새벽 7시 전", ancestorIds = content),
            CartAssistPolicy.Node(text = "5일 남음", ancestorIds = content),
            CartAssistPolicy.Node(text = "구매 1위", ancestorIds = content),
            CartAssistPolicy.Node(text = "브랜드샵", ancestorIds = content),
        )
        val result = requireNotNull(ProductLinkImportPolicy.suggest(nodes))
        assertEquals(listOf("돌 스위티오 바나나, 1kg 내외, 1개"), result.titleCandidates)
        assertEquals(listOf("1kg 내외, 1개"), result.optionCandidates)
        assertNull(ProductLinkImportPolicy.suggest(nodes.filterNot { it.resourceId == CartAssistPolicy.BOTTOM_BUTTONS_ID }))
    }

    @Test fun labeledSplitOptionIsReadWithoutInventingMissingUnits() {
        val content = listOf(CartAssistPolicy.PREFIX + "web_layout")
        fun nodes(lines: List<String>) = detail() + lines.map { CartAssistPolicy.Node(text = it, ancestorIds = content) }
        assertEquals(listOf("285g × 1세트"), requireNotNull(ProductLinkImportPolicy.suggest(nodes(listOf(
            "동원 용기죽 4종 기획세트", "개당 중량 × 수량", "285g", "×", "1세트",
        )))).optionCandidates)
        assertTrue(requireNotNull(ProductLinkImportPolicy.suggest(nodes(listOf(
            "동원 용기죽 4종 기획세트", "개당 중량 × 수량", "2859", "×", "1세트",
        )))).optionCandidates.isEmpty())
    }
}
