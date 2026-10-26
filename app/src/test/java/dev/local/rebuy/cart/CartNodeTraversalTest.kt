package dev.local.rebuy.cart

import org.junit.Assert.*
import org.junit.Test

class CartNodeTraversalTest {
    private data class Tree(val id: String, val children: List<Tree> = emptyList(), val accepted: Boolean = true)

    private fun tree(depth: Int): Tree = if (depth == 0) Tree("leaf")
        else Tree("content", listOf(tree(depth - 1), tree(depth - 1)))

    @Test fun largeDetailSubtreeDoesNotHideLaterBottomButtonSibling() {
        val root = Tree("root", listOf(
            tree(10),
            Tree("bottom", listOf(Tree("buttons", listOf(Tree("clickable", listOf(Tree("cart-label"))))))),
        ))
        var lookups = 0
        val scan = CartNodeTraversal.scan(root, { it.accepted }, { it.id }, { it.children.size },
            { node, index -> lookups += 1; node.children[index] })
        assertTrue(scan.truncated)
        assertEquals(600, scan.entries.size)
        assertEquals(599, lookups)
        val label = scan.entries.single { it.resourceId == "cart-label" }
        assertEquals(listOf("root", "bottom", "buttons", "clickable"), label.ancestorIds)
        val parent = scan.entries[requireNotNull(label.parentIndex)]
        assertEquals("clickable", parent.resourceId)
        assertEquals(1, parent.childCount)
    }

    @Test fun packageBoundaryIsNotExpandedAndDepthRemainsBounded() {
        val root = Tree("root", listOf(
            Tree("outside", listOf(Tree("outside-child")), accepted = false),
            Tree("inside", listOf(Tree("deep", listOf(Tree("too-deep"))))),
        ))
        val expanded = mutableListOf<String>()
        val scan = CartNodeTraversal.scan(root, { it.accepted }, { it.id }, { it.children.size },
            { node, index -> expanded += node.id; node.children[index] }, maxDepth = 2)
        assertTrue(scan.truncated)
        assertEquals(listOf("root", "inside", "deep"), scan.entries.map { it.resourceId })
        assertFalse(expanded.contains("outside"))
    }

    @Test fun missingChildrenCannotCauseUnboundedNativeLookups() {
        var lookups = 0
        val scan = CartNodeTraversal.scan(Tree("root"), { true }, { it.id }, { 100_000 },
            { _, _ -> lookups += 1; null })
        assertTrue(scan.truncated)
        assertEquals(1, scan.entries.size)
        assertEquals(599, lookups)
    }
}
