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
}
