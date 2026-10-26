package dev.local.rebuy.cart

import java.text.Normalizer

/** Presence flags only; no native text or identifiers leave the inspection boundary. */
data class CartDetailStructure(
    val bottomContainer: Boolean = false,
    val buttonContainer: Boolean = false,
    val detailContent: Boolean = false,
    val cartLabel: Boolean = false,
    val buyLabel: Boolean = false,
) {
    val complete: Boolean get() = bottomContainer && buttonContainer && detailContent && cartLabel && buyLabel
}

/** Pure, conservative rules derived from the inspected Coupang 9.4.5 accessibility tree. */
internal object CartAssistPolicy {
    const val PREFIX = "com.coupang.mobile:id/"
    const val CART_TEXT_ID = PREFIX + "text_view"
    const val BUTTON_LAYOUT_ID = PREFIX + "button_layout_container"
    const val BOTTOM_BUTTONS_ID = PREFIX + "bottom_buttons_container"
    val DETAIL_CONTENT_IDS = setOf(PREFIX + "web_layout", PREFIX + "recycler_view", PREFIX + "scroller_layout")
    val SCROLL_IDS = setOf(PREFIX + "recycler_view", PREFIX + "scroller_layout")
    private val detailScrollOrder = listOf(PREFIX + "recycler_view", PREFIX + "scroller_layout")

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

    private const val NUMBER = "[0-9]+(?:\\.[0-9]+)?"
    private val weightMultiplication = Regex(
        "(?<![\\p{L}\\p{N}])($NUMBER\\s*(?:밀리리터|킬로그램|밀리그램|리터|그램|kg|mg|ml|g|l)\\s*)[x×*](?=\\s*[0-9])",
    )
    private val numericMultiplication = Regex(
        "(?<![\\p{L}\\p{N}])$NUMBER(?:\\s*[x×*]\\s*$NUMBER)+",
    )
    private val measuredOptionLabel = Regex(
        "^\\s*(?:개당\\s*)?(?:중량|용량)\\s*[x×*]\\s*수량\\s*[:：]?\\s*(?=[0-9])",
    )

    /** Multiplication spellings are equivalent only in numeric option context, never in titles. */
    fun normalizeOption(value: String): String {
        val text = measuredOptionLabel.replace(
            Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT), "",
        )
        val withoutWeightSeparator = weightMultiplication.replace(text) { it.groupValues[1] + " " }
        // Keep a marker between bare numbers: 10 x 20 must not accidentally become 1020.
        val multiplication = numericMultiplication.replace(withoutWeightSeparator) { match ->
            match.value.replace(Regex("[x×*]"), "x")
        }
        return multiplication.filterIndexed { index, character ->
            character.isLetterOrDigit() || (character == '.' &&
                multiplication.getOrNull(index - 1)?.isDigit() == true &&
                multiplication.getOrNull(index + 1)?.isDigit() == true)
        }
    }

    fun detailStructure(nodes: List<Node>): CartDetailStructure = CartDetailStructure(
        bottomContainer = nodes.any { it.visible && it.resourceId == BOTTOM_BUTTONS_ID },
        buttonContainer = nodes.any { it.visible && it.resourceId == BUTTON_LAYOUT_ID },
        detailContent = nodes.any { it.visible && it.resourceId in DETAIL_CONTENT_IDS },
        cartLabel = nodes.any { it.visible && it.resourceId == CART_TEXT_ID && normalize(it.text) == "장바구니담기" && BOTTOM_BUTTONS_ID in it.ancestorIds },
        buyLabel = nodes.any { it.visible && it.resourceId == CART_TEXT_ID && normalize(it.text) == "바로구매" && BOTTOM_BUTTONS_ID in it.ancestorIds },
    )

    fun isDetailScreen(nodes: List<Node>): Boolean = detailStructure(nodes).complete

    /** At most one attempt per observed container, inner list first; true means accepted. */
    fun requestDetailScroll(
        nodes: List<Node>,
        isScrollable: (Int) -> Boolean,
        scrollForward: (Int) -> Boolean,
    ): Boolean {
        if (!isDetailScreen(nodes)) return false
        for (id in detailScrollOrder) {
            val index = nodes.indices.firstOrNull { index ->
                val node = nodes[index]
                node.visible && node.enabled && node.resourceId == id && isScrollable(index)
            } ?: continue
            if (scrollForward(index)) return true
        }
        return false
    }

    fun cartTargetIndex(nodes: List<Node>, expectedTitle: String, expectedOption: String): Int? =
        inspectCartTarget(nodes, expectedTitle, expectedOption).targetIndex

    fun buyNowTargetIndex(nodes: List<Node>, expectedTitle: String, expectedOption: String): Int? =
        inspectDetailTarget(nodes, expectedTitle, expectedOption, "바로구매").targetIndex

    data class DetailInspection(
        val detailScreen: Boolean,
        val titleMatched: Boolean,
        val optionMatched: Boolean,
        val buttonMatched: Boolean,
        val targetIndex: Int?,
    )

    fun inspectCartTarget(nodes: List<Node>, expectedTitle: String, expectedOption: String): DetailInspection =
        inspectDetailTarget(nodes, expectedTitle, expectedOption, "장바구니담기")

    private fun inspectDetailTarget(nodes: List<Node>, expectedTitle: String, expectedOption: String, targetText: String): DetailInspection {
        val detailScreen = isDetailScreen(nodes)
        val title = normalize(expectedTitle)
        val option = normalizeOption(expectedOption)
        // Text in headers, carts, popups, or bottom recommendations is not a product identity anchor.
        val content = nodes.filter { node ->
            node.visible && node.ancestorIds.any { it in DETAIL_CONTENT_IDS } &&
                node.ancestorIds.none { it.contains("recommend", ignoreCase = true) }
        }
        val matchedTitles = content.filter { normalize(it.text) == title }
        val titleMatched = title.length >= 4 && matchedTitles.isNotEmpty()
        val optionMatched = option.length >= 2 && (content.any {
            normalizeOption(it.text.trim().removePrefix("옵션")).removePrefix("옵션") == option
        } || (titleMatched && matchedTitles.any { optionInMatchedTitle(it.text, option) }))
        val buttonIndex = if (detailScreen) strictButtonIndex(nodes, targetText) else null
        return DetailInspection(detailScreen, titleMatched, optionMatched, buttonIndex != null,
            buttonIndex?.takeIf { titleMatched && optionMatched })
    }

    private val titlePackSuffix = Regex(
        ",\\s*($NUMBER\\s*(?:밀리리터|킬로그램|밀리그램|리터|그램|kg|mg|ml|g|l)" +
            "(?:\\s*내외)?\\s*,\\s*$NUMBER\\s*(?:개|팩|봉|병|세트|묶음))\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /** Only an explicit measured/count suffix on the same exactly matched title is evidence. */
    private fun optionInMatchedTitle(text: String, expectedOption: String): Boolean {
        val title = Normalizer.normalize(text, Normalizer.Form.NFKC)
        val match = titlePackSuffix.find(title) ?: return false
        if (normalize(title.substring(0, match.range.first)).length < 4) return false
        return normalizeOption(match.groupValues[1]) == expectedOption
    }


    private fun strictButtonIndex(nodes: List<Node>, targetText: String): Int? {
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
        nodes.any { it.visible && (normalize(it.text).contains("담기완료") || normalize(it.text) == "장바구니에상품을담았습니다") } &&
            nodes.any { it.visible && normalize(it.text) == "장바구니가기" }
}
