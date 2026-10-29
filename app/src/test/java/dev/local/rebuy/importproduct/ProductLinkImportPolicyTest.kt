package dev.local.rebuy.importproduct

import dev.local.rebuy.cart.CartAssistPolicy
import org.junit.Assert.*
import org.junit.Test

class ProductLinkImportPolicyTest {
    private fun primaryTitle(text: String, ancestors: List<String>) = CartAssistPolicy.Node(
        text = text,
        resourceId = CartAssistPolicy.PREFIX + "name_text",
        ancestorIds = ancestors + (CartAssistPolicy.PREFIX + "name_layout"),
    )

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
            primaryTitle("돌 스위티오 바나나, 1kg 내외, 1개", content),
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
        fun nodes(lines: List<String>) = detail() + lines.mapIndexed { index, line ->
            if (index == 0) primaryTitle(line, content)
            else CartAssistPolicy.Node(text = line, ancestorIds = content)
        }
        assertEquals(listOf("285g × 1세트"), requireNotNull(ProductLinkImportPolicy.suggest(nodes(listOf(
            "동원 용기죽 4종 기획세트", "개당 중량 × 수량", "285g", "×", "1세트",
        )))).optionCandidates)
        assertTrue(requireNotNull(ProductLinkImportPolicy.suggest(nodes(listOf(
            "동원 용기죽 4종 기획세트", "개당 중량 × 수량", "2859", "×", "1세트",
        )))).optionCandidates.isEmpty())
    }

    @Test fun nativeEggTitleWinsWhenOriginPrecedesTitleInTraversalAndRankIsSplit() {
        val content = listOf(CartAssistPolicy.PREFIX + "recycler_view")
        val title = "곰곰 촉촉 반숙란 (냉장), 15구, 1개"
        val nodes = detail() + listOf(
            CartAssistPolicy.Node(text = "구운계란/훈제란", ancestorIds = content),
            CartAssistPolicy.Node(text = "구매 2위", ancestorIds = content),
            CartAssistPolicy.Node(text = "원산지: 상품 상세설명 참조", ancestorIds = content),
            primaryTitle(title, content),
            CartAssistPolicy.Node(text = "나의 위시리스트로 해당 상품 추가", ancestorIds = content),
            CartAssistPolicy.Node(text = "15구, 1개", ancestorIds = content),
        )
        val result = requireNotNull(ProductLinkImportPolicy.suggest(nodes))
        assertEquals(listOf(title), result.titleCandidates)
        assertEquals(listOf("15구, 1개"), result.optionCandidates)
    }

    @Test fun hiddenRecommendationAndOutsideNativeNamesDoNotReplacePrimaryTitle() {
        val content = listOf(CartAssistPolicy.PREFIX + "recycler_view")
        val recommendation = content + (CartAssistPolicy.PREFIX + "recommendation_layout")
        val title = "곰곰 촉촉 반숙란 (냉장), 15구, 1개"
        val nodes = detail() + listOf(
            primaryTitle("길고 상세한 추천 상품의 다른 이름", recommendation),
            CartAssistPolicy.Node(text = "원산지: 추천 상품 설명 참조", ancestorIds = recommendation),
            primaryTitle("길고 상세한 화면 밖 다른 상품 이름", emptyList()),
            CartAssistPolicy.Node(text = "원산지: 화면 밖 설명 참조"),
            primaryTitle("길고 상세한 숨겨진 다른 상품 이름", content).copy(visible = false),
            CartAssistPolicy.Node(text = "원산지: 숨겨진 상품 설명 참조", ancestorIds = content, visible = false),
            CartAssistPolicy.Node(text = "원산지: 상품 상세설명 참조", ancestorIds = content),
            primaryTitle(title, content),
        )
        assertEquals(listOf(title), requireNotNull(ProductLinkImportPolicy.suggest(nodes)).titleCandidates)
    }

    @Test fun nativeNameProvidesCurrentOptionInsteadOfEarlierPackagingAndWishlistDecoys() {
        val content = listOf(CartAssistPolicy.PREFIX + "recycler_view")
        val title = "곰곰 촉촉 반숙란 (냉장), 15구, 1개"
        val nodes = detail() + listOf(
            CartAssistPolicy.Node(text = "30구, 2개", ancestorIds = content),
            CartAssistPolicy.Node(text = "나의 위시리스트로 해당 상품 추가", ancestorIds = content),
            CartAssistPolicy.Node(text = "구운계란/훈제란", ancestorIds = content),
            primaryTitle(title, content),
        )
        val result = requireNotNull(ProductLinkImportPolicy.suggest(nodes))
        assertEquals(listOf(title), result.titleCandidates)
        assertEquals(listOf("15구, 1개"), result.optionCandidates)
    }
}
