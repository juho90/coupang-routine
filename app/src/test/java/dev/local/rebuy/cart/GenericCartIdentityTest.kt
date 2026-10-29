package dev.local.rebuy.cart

import org.junit.Assert.*
import org.junit.Test

class GenericCartIdentityTest {
    private val title = "곰곰 촉촉 반숙란 (냉장), 15구, 1개"
    private val bottom = listOf(CartAssistPolicy.BOTTOM_BUTTONS_ID, CartAssistPolicy.BUTTON_LAYOUT_ID)
    private val content = listOf(CartAssistPolicy.PREFIX + "web_layout", CartAssistPolicy.PREFIX + "recycler_view")
    private val nameContent = content + (CartAssistPolicy.PREFIX + "name_layout")

    private fun detail(selectedTitle: String = title) = listOf(
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.BOTTOM_BUTTONS_ID),
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.BUTTON_LAYOUT_ID),
        CartAssistPolicy.Node(resourceId = CartAssistPolicy.PREFIX + "web_layout"),
        CartAssistPolicy.Node(text = "원산지: 국내산", ancestorIds = content),
        CartAssistPolicy.Node(text = selectedTitle, resourceId = CartAssistPolicy.PREFIX + "name_text", ancestorIds = nameContent),
        CartAssistPolicy.Node(text = "15구, 1개", ancestorIds = content),
        CartAssistPolicy.Node(clickable = true, childCount = 1, ancestorIds = bottom),
        CartAssistPolicy.Node(text = "장바구니 담기", resourceId = CartAssistPolicy.CART_TEXT_ID, parentIndex = 6, ancestorIds = bottom),
        CartAssistPolicy.Node(text = "바로구매", resourceId = CartAssistPolicy.CART_TEXT_ID, ancestorIds = bottom),
    )

    private fun inspect(nodes: List<CartAssistPolicy.Node>, expectedTitle: String = title, option: String = "15구, 1개") =
        CartAssistPolicy.inspectCartTarget(nodes, expectedTitle, option, requirePrimaryOption = true)

    @Test fun uniqueNativeNameLeafSuppliesItsCountSuffixRegardlessOfOriginOrderOrPresence() {
        val nodes = detail()
        val result = inspect(nodes)
        assertEquals(nodes[4], CartAssistPolicy.primaryTitleNode(nodes))
        assertTrue(result.detailScreen)
        assertTrue(result.titleMatched)
        assertTrue(result.optionMatched)
        assertTrue(result.buttonMatched)
        assertEquals(6, result.targetIndex)
        val reversed = nodes.toMutableList().also { it[3] = nodes[4]; it[4] = nodes[3] }
        assertEquals(6, inspect(reversed).targetIndex)
        assertEquals(6, inspect(nodes.mapIndexed { index, node -> if (index == 3) node.copy(text = "") else node }).targetIndex)
        assertEquals(6, inspect(nodes + nodes[3]).targetIndex)
        assertEquals(6, inspect(nodes.mapIndexed { index, node -> if (index == 4) node.copy(enabled = false) else node }).targetIndex)
        assertNull(inspect(nodes, option = "15구, 2개").targetIndex)
        assertNull(inspect(nodes, option = "20구, 1개").targetIndex)
    }

    @Test fun unselectedFifteenPackTextCannotValidateTheSelectedTwentyPackTitle() {
        val selected = "곰곰 촉촉 반숙란 (냉장), 20구, 1개"
        val nodes = detail(selected)
        assertEquals("15구, 1개", nodes[5].text)
        val result = inspect(nodes, selected)
        assertTrue(result.titleMatched)
        assertFalse(result.optionMatched)
        assertTrue(result.buttonMatched)
        assertNull(result.targetIndex)
        assertEquals(6, inspect(nodes, selected, "20구, 1개").targetIndex)
    }

    @Test fun missingWrongHiddenOrDuplicateNativeNameAnchorsRejectPrimaryIdentity() {
        val nodes = detail()
        fun replaceName(change: (CartAssistPolicy.Node) -> CartAssistPolicy.Node) =
            nodes.mapIndexed { index, node -> if (index == 4) change(node) else node }
        val invalidTrees = listOf(
            replaceName { it.copy(resourceId = "") },
            replaceName { it.copy(resourceId = CartAssistPolicy.PREFIX + "other_name_text") },
            replaceName { it.copy(ancestorIds = content) },
            replaceName { it.copy(ancestorIds = listOf(CartAssistPolicy.PREFIX + "name_layout")) },
            replaceName { it.copy(visible = false) },
            replaceName { it.copy(childCount = 1) },
            replaceName { it.copy(text = "") },
            nodes + nodes[4],
        )
        for (invalid in invalidTrees) {
            assertNull(CartAssistPolicy.primaryTitleNode(invalid))
            val result = inspect(invalid)
            assertFalse(result.titleMatched)
            assertFalse(result.optionMatched)
            assertTrue(result.buttonMatched)
            assertNull(result.targetIndex)
        }
        assertEquals(6, inspect(nodes + nodes[4].copy(visible = false)).targetIndex)
    }

    @Test fun matchingRecommendationOrOtherDetailTextCannotReplaceThePrimaryProductTitle() {
        val selected = "곰곰 촉촉 반숙란 (냉장), 20구, 1개"
        val nodes = detail(selected)
        val elsewhere = listOf(
            CartAssistPolicy.Node(text = title, resourceId = CartAssistPolicy.PREFIX + "name_text", ancestorIds = nameContent + "recommendation_list"),
            CartAssistPolicy.Node(text = title, resourceId = CartAssistPolicy.PREFIX + "name_text", ancestorIds = nameContent + "ranking_list"),
            CartAssistPolicy.Node(text = title, ancestorIds = content),
            CartAssistPolicy.Node(text = title),
        )
        for (matchingText in elsewhere) {
            val result = inspect(nodes + matchingText)
            assertFalse(result.titleMatched)
            assertFalse(result.optionMatched)
            assertNull(result.targetIndex)
        }
        val recommendationAnchor = detail().mapIndexed { index, node ->
            if (index == 4) node.copy(ancestorIds = nameContent + "recommendation_list") else node
        }
        assertNull(CartAssistPolicy.primaryTitleNode(recommendationAnchor))
        assertNull(inspect(recommendationAnchor).targetIndex)
        val rankingAnchor = detail().mapIndexed { index, node ->
            if (index == 4) node.copy(ancestorIds = nameContent + "ranking_list") else node
        }
        assertNull(CartAssistPolicy.primaryTitleNode(rankingAnchor))
        assertNull(inspect(rankingAnchor).targetIndex)
    }

    @Test fun nativeNameStillRequiresAnExplicitPackSuffixWithoutExtraProductWords() {
        val withoutComma = "곰곰 촉촉 반숙란 (냉장) 15구, 1개"
        assertNull(inspect(detail(withoutComma), withoutComma).targetIndex)
        val trailingWords = title + " 사은품"
        assertNull(inspect(detail(trailingWords), trailingWords).targetIndex)
        assertNull(inspect(detail(), option = "구").targetIndex)
    }
}
