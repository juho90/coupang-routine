package dev.local.rebuy.cart

import java.text.Normalizer

/** Pure, conservative rules derived from the inspected Coupang 9.4.5 accessibility tree. */
internal object CartAssistPolicy {
    const val PREFIX = "com.coupang.mobile:id/"
    const val CART_TEXT_ID = PREFIX + "text_view"
    const val BUTTON_LAYOUT_ID = PREFIX + "button_layout_container"
    const val BOTTOM_BUTTONS_ID = PREFIX + "bottom_buttons_container"
    val DETAIL_CONTENT_IDS = setOf(PREFIX + "web_layout", PREFIX + "recycler_view", PREFIX + "scroller_layout")
    val SCROLL_IDS = setOf(PREFIX + "recycler_view", PREFIX + "scroller_layout")

    data class Node(
        val text: String = "",
        val resourceId: String = "",
        val parentIndex: Int? = null,
        val ancestorIds: List<String> = emptyList(),
        val visible: Boolean = true,
        val enabled: Boolean = true,
        val clickable: Boolean = false,
        val childCount: Int = 0,
    )

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit)

    fun isDetailScreen(nodes: List<Node>): Boolean =
        nodes.any { it.visible && it.resourceId == BOTTOM_BUTTONS_ID } &&
            nodes.any { it.visible && it.resourceId == BUTTON_LAYOUT_ID } &&
            nodes.any { it.visible && it.resourceId in DETAIL_CONTENT_IDS } &&
            nodes.any { it.visible && it.resourceId == CART_TEXT_ID && normalize(it.text) == "장바구니담기" && BOTTOM_BUTTONS_ID in it.ancestorIds } &&
            nodes.any { it.visible && it.resourceId == CART_TEXT_ID && normalize(it.text) == "바로구매" && BOTTOM_BUTTONS_ID in it.ancestorIds }

    fun cartTargetIndex(nodes: List<Node>, expectedTitle: String, expectedOption: String): Int? =
        detailTargetIndex(nodes, expectedTitle, expectedOption, "장바구니담기")

    fun buyNowTargetIndex(nodes: List<Node>, expectedTitle: String, expectedOption: String): Int? =
        detailTargetIndex(nodes, expectedTitle, expectedOption, "바로구매")

    private fun detailTargetIndex(nodes: List<Node>, expectedTitle: String, expectedOption: String, targetText: String): Int? {
        if (!isDetailScreen(nodes)) return null
        val title = normalize(expectedTitle)
        val option = normalize(expectedOption)
        if (title.length < 4 || option.length < 2) return null
        // Text in headers, carts, popups, or bottom recommendations is not a product identity anchor.
        val content = nodes.filter { node ->
            node.visible && node.ancestorIds.any { it in DETAIL_CONTENT_IDS } &&
                node.ancestorIds.none { it.contains("recommend", ignoreCase = true) }
        }
        if (content.none { normalize(it.text) == title }) return null
        if (content.none { normalize(it.text).removePrefix("옵션") == option }) return null
        val labels = nodes.withIndex().filter { (_, node) ->
            node.visible && node.enabled && node.resourceId == CART_TEXT_ID &&
                normalize(node.text) == targetText &&
                BUTTON_LAYOUT_ID in node.ancestorIds && BOTTOM_BUTTONS_ID in node.ancestorIds
        }
        if (labels.size != 1) return null
        val label = labels.single()
        val parentIndex = label.value.parentIndex ?: return null
        val parent = nodes.getOrNull(parentIndex) ?: return null
        // The inspected click target is the label's immediate one-child ViewGroup, never a shared
        // container containing both cart and purchase buttons.
        if (!parent.visible || !parent.enabled || !parent.clickable || parent.childCount != 1) return null
        if (parent.resourceId.isNotEmpty() || BUTTON_LAYOUT_ID !in parent.ancestorIds || BOTTOM_BUTTONS_ID !in parent.ancestorIds) return null
        if (nodes.any { it.parentIndex == parentIndex && normalize(it.text) != targetText }) return null
        return parentIndex
    }

    fun hasConfirmedAddition(nodes: List<Node>): Boolean =
        nodes.any { it.visible && normalize(it.text).contains("담기완료") } &&
            nodes.any { it.visible && normalize(it.text) == "장바구니가기" }
}
