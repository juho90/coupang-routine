package dev.local.rebuy.cart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CartAssistPolicyTest {
    private val bottom = listOf(CartAssistPolicy.BOTTOM_BUTTONS_ID, CartAssistPolicy.BUTTON_LAYOUT_ID)
    private val content = listOf(CartAssistPolicy.PREFIX + "web_layout", CartAssistPolicy.PREFIX + "recycler_view")

    private fun detail(): List<CartAssistPolicy.Node> = listOf(
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.BOTTOM_BUTTONS_ID),
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.BUTTON_LAYOUT_ID),
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.PREFIX + "web_layout"),
        CartAssistPolicy.Node(text = "익스트림 트리플 아르기닌6200", ancestorIds = content),
        CartAssistPolicy.Node(text = "600ml, 2개", ancestorIds = content),
        CartAssistPolicy.Node(clickable = true, childCount = 1, ancestorIds = bottom),
        CartAssistPolicy.Node(text = "장바구니 담기", resourceId = CartAssistPolicy.CART_TEXT_ID, parentIndex = 5, ancestorIds = bottom),
        CartAssistPolicy.Node(text = "바로구매", resourceId = CartAssistPolicy.CART_TEXT_ID, ancestorIds = bottom),
    )

    private fun target(nodes: List<CartAssistPolicy.Node>) =
        CartAssistPolicy.cartTargetIndex(nodes, "익스트림 트리플 아르기닌6200", "600ml,2개")

    @Test fun verifiedTitleOptionAndObservedButtonSelectOnlyItsImmediateParent() {
        assertEquals(5, target(detail()))
    }

    @Test fun neverClicksWhenOptionOrTitleDoesNotMatchExactly() {
        assertNull(target(detail().mapIndexed { index, node -> if (index == 4) node.copy(text = "600ml, 3개") else node }))
        assertNull(target(detail().mapIndexed { index, node -> if (index == 3) node.copy(text = "익스트림 트리플 아르기닌7000") else node }))
    }

    @Test fun rejectsRecommendationIdentityAndOtherScreens() {
        assertNull(target(detail().mapIndexed { index, node -> if (index == 3) node.copy(ancestorIds = content + "recommendation_list") else node }))
        assertNull(target(detail().filterNot { it.resourceId == CartAssistPolicy.BOTTOM_BUTTONS_ID }))
        assertNull(target(detail().mapIndexed { index, node -> if (index == 3) node.copy(ancestorIds = emptyList()) else node }))
    }

    @Test fun neverSelectsSharedParentOrPurchaseButtonOrHiddenDisabledButton() {
        assertNull(target(detail().mapIndexed { index, node -> if (index == 5) node.copy(childCount = 2) else node }))
        assertNull(target(detail().mapIndexed { index, node -> if (index == 6) node.copy(text = "바로구매") else node }))
        assertNull(target(detail().mapIndexed { index, node -> if (index == 6) node.copy(visible = false) else node }))
        assertNull(target(detail().mapIndexed { index, node -> if (index == 5) node.copy(enabled = false) else node }))
        assertNull(target(detail() + detail()[6]))
    }

    @Test fun completionRequiresBothObservedCompletionAndCartLink() {
        val popup = listOf(CartAssistPolicy.Node(text = "7,500원 할인받고 담기 완료"), CartAssistPolicy.Node(text = "장바구니 가기"))
        assertTrue(CartAssistPolicy.hasConfirmedAddition(popup))
        assertFalse(CartAssistPolicy.hasConfirmedAddition(popup.take(1)))
        assertFalse(CartAssistPolicy.hasConfirmedAddition(listOf(CartAssistPolicy.Node(text = "장바구니 담기"))))
    }

    @Test fun normalizationHandlesSpacingAndPackPunctuationWithoutChangingTheCount() {
        assertEquals("600ml2개", CartAssistPolicy.normalize("６００ｍｌ, ２개"))
        assertFalse(CartAssistPolicy.normalize("600ml,2개") == CartAssistPolicy.normalize("600ml,3개"))
    }

    @Test fun optionMultiplicationSpellingsMatchTheSameWeightAndPackCount() {
        val expected = CartAssistPolicy.normalizeOption("285g1세트")
        listOf("285g × 1세트", "285g x 1세트", "285gX1세트", "285g*1세트", "285gx1세트", "２８５ｇ Ｘ １세트").forEach { option ->
            assertEquals(option, expected, CartAssistPolicy.normalizeOption(option))
        }
        listOf("285kg", "285mg", "285ml", "285l", "285그램", "285밀리그램", "285킬로그램", "285리터", "285밀리리터").forEach { measure ->
            assertEquals(CartAssistPolicy.normalizeOption("${measure}1세트"), CartAssistPolicy.normalizeOption("$measure x 1세트"))
        }
    }

    @Test fun optionNormalizationKeepsDifferentNumbersUnitsAndModelLettersDistinct() {
        val expected = CartAssistPolicy.normalizeOption("285g × 1세트")
        listOf("285gx2세트", "286gx1세트", "285mgx1세트", "28.5gx1세트").forEach { different ->
            assertFalse(different, expected == CartAssistPolicy.normalizeOption(different))
        }
        assertEquals("modelx", CartAssistPolicy.normalizeOption("Model X"))
        assertEquals("xl", CartAssistPolicy.normalizeOption("XL"))
        assertEquals("model2x1", CartAssistPolicy.normalizeOption("Model2X1"))
        assertFalse(CartAssistPolicy.normalizeOption("Model X") == CartAssistPolicy.normalizeOption("Model"))
        assertFalse(CartAssistPolicy.normalizeOption("XL") == CartAssistPolicy.normalizeOption("L"))
        assertEquals(CartAssistPolicy.normalizeOption("10 × 20"), CartAssistPolicy.normalizeOption("10x20"))
        assertFalse(CartAssistPolicy.normalizeOption("10x20") == CartAssistPolicy.normalizeOption("1020"))
        // The existing title normalizer retains model letters and is intentionally unchanged.
        assertEquals("285gx1세트", CartAssistPolicy.normalize("285g x 1세트"))
    }

    @Test fun actualDetailOptionUsesMultiplicationNormalizationBeforeSelectingTheCartButton() {
        val nodes = detail().mapIndexed { index, node -> if (index == 4) node.copy(text = "285g × 1세트") else node }
        assertEquals(5, CartAssistPolicy.cartTargetIndex(nodes, "익스트림 트리플 아르기닌6200", "285g x 1세트"))
        assertNull(CartAssistPolicy.cartTargetIndex(nodes, "익스트림 트리플 아르기닌6200", "285g x 2세트"))
        val labelled = nodes.mapIndexed { index, node -> if (index == 4) node.copy(text = "옵션285gx1세트") else node }
        assertEquals(5, CartAssistPolicy.cartTargetIndex(labelled, "익스트림 트리플 아르기닌6200", "285g × 1세트"))
    }

    @Test fun observedFoldedOptionLabelMatchesWithoutDiscardingArbitraryProductWords() {
        val folded = detail().mapIndexed { index, node -> when (index) {
            3 -> node.copy(text = "동원 용기죽 4종 기획세트", resourceId = CartAssistPolicy.PREFIX + "name_text")
            4 -> node.copy(text = "개당 중량 × 수량 285g × 1세트",
                ancestorIds = content + listOf(CartAssistPolicy.PREFIX + "layout_container", CartAssistPolicy.PREFIX + "layout_folded"))
            else -> node
        } }
        assertEquals(5, CartAssistPolicy.cartTargetIndex(folded, "동원 용기죽 4종 기획세트", "285g x 1세트"))
        assertNull(CartAssistPolicy.cartTargetIndex(folded, "동원 용기죽 4종 기획세트", "285g x 2세트"))
        assertEquals(CartAssistPolicy.normalizeOption("600ml,2개"), CartAssistPolicy.normalizeOption("용량 × 수량: 600ml, 2개"))
        assertFalse(CartAssistPolicy.normalizeOption("추천 중량 × 수량 285g × 1세트") == CartAssistPolicy.normalizeOption("285gx1세트"))
        assertFalse(CartAssistPolicy.normalizeOption("색상 × 수량 285g × 1세트") == CartAssistPolicy.normalizeOption("285gx1세트"))
    }

    @Test fun diagnosticsIdentifyMissingIdentitySeparatelyFromStrictButtonShape() {
        val matched = CartAssistPolicy.inspectCartTarget(detail(), "익스트림 트리플 아르기닌6200", "600ml,2개")
        assertTrue(matched.detailScreen)
        assertTrue(matched.titleMatched)
        assertTrue(matched.optionMatched)
        assertTrue(matched.buttonMatched)
        assertEquals(5, matched.targetIndex)
        val wrongOption = CartAssistPolicy.inspectCartTarget(detail(), "익스트림 트리플 아르기닌6200", "600ml,3개")
        assertTrue(wrongOption.titleMatched)
        assertFalse(wrongOption.optionMatched)
        assertTrue(wrongOption.buttonMatched)
        assertNull(wrongOption.targetIndex)
        val noButton = CartAssistPolicy.inspectCartTarget(detail().mapIndexed { index, node ->
            if (index == 5) node.copy(childCount = 2) else node
        }, "익스트림 트리플 아르기닌6200", "600ml,2개")
        assertTrue(noButton.titleMatched)
        assertTrue(noButton.optionMatched)
        assertFalse(noButton.buttonMatched)
        assertNull(noButton.targetIndex)
    }

    private fun scrollableDetail() = detail() + listOf(
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.PREFIX + "scroller_layout"),
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.PREFIX + "recycler_view"),
    )

    @Test fun detailScrollPrefersInnerListAndStopsAfterAcceptedRequest() {
        val actions = mutableListOf<Int>()
        assertTrue(CartAssistPolicy.requestDetailScroll(scrollableDetail(), { true }) { actions += it; true })
        assertEquals(listOf(9), actions)
    }

    @Test fun rejectedInnerScrollFallsBackOnceToOuterWithoutRepeatingEitherContainer() {
        val nodes = scrollableDetail() + CartAssistPolicy.Node(resourceId = CartAssistPolicy.PREFIX + "recycler_view")
        val actions = mutableListOf<Int>()
        assertTrue(CartAssistPolicy.requestDetailScroll(nodes, { true }) { actions += it; it == 8 })
        assertEquals(listOf(9, 8), actions)
        actions.clear()
        assertFalse(CartAssistPolicy.requestDetailScroll(nodes, { true }) { actions += it; false })
        assertEquals(listOf(9, 8), actions)
    }

    @Test fun scrollNeverTouchesOtherScreensOrHiddenDisabledAndNonScrollableContainers() {
        val actions = mutableListOf<Int>()
        val noDetail = scrollableDetail().filterNot { it.resourceId == CartAssistPolicy.BOTTOM_BUTTONS_ID }
        assertFalse(CartAssistPolicy.requestDetailScroll(noDetail, { true }) { actions += it; true })
        val unavailable = scrollableDetail().mapIndexed { index, node -> when (index) {
            8 -> node.copy(visible = false)
            9 -> node.copy(enabled = false)
            else -> node
        } }
        assertFalse(CartAssistPolicy.requestDetailScroll(unavailable, { true }) { actions += it; true })
        assertFalse(CartAssistPolicy.requestDetailScroll(scrollableDetail(), { false }) { actions += it; true })
        assertTrue(actions.isEmpty())
    }
}
