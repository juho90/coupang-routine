package dev.local.rebuy.cart

import java.util.ArrayDeque

/** Breadth-first snapshot with a shared bound on native child lookups and retained nodes. */
internal object CartNodeTraversal {
    const val VISIBLE_SCAN_LIMIT = 2_000
    data class Entry<T>(
        val node: T,
        val resourceId: String,
        val parentIndex: Int?,
        val ancestorIds: List<String>,
        val childCount: Int,
    )

    data class Result<T>(val entries: List<Entry<T>>, val truncated: Boolean)

    private data class Pending<T>(val node: T, val parent: Int?, val ancestors: List<String>, val depth: Int)

    fun <T> scan(
        root: T,
        accepts: (T) -> Boolean,
        resourceId: (T) -> String,
        childCount: (T) -> Int,
        childAt: (T, Int) -> T?,
        maxNodes: Int = 600,
        maxDepth: Int = 35,
    ): Result<T> {
        require(maxNodes > 0 && maxDepth >= 0)
        val result = mutableListOf<Entry<T>>()
        val pending = ArrayDeque<Pending<T>>()
        pending.add(Pending(root, null, emptyList(), 0))
        var childLookups = 0
        var truncated = false
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (!accepts(next.node)) continue
            val id = resourceId(next.node)
            val children = childCount(next.node).coerceAtLeast(0)
            val index = result.size
            result += Entry(next.node, id, next.parent, next.ancestors, children)
            if (next.depth >= maxDepth) {
                if (children > 0) truncated = true
                continue
            }
            val ancestors = if (id.isEmpty()) next.ancestors else next.ancestors + id
            val available = (maxNodes - 1 - childLookups).coerceAtLeast(0)
            val count = minOf(children, available)
            if (count < children) truncated = true
            repeat(count) { childIndex ->
                childLookups += 1
                childAt(next.node, childIndex)?.let { child ->
                    pending.addLast(Pending(child, index, ancestors, next.depth + 1))
                }
            }
        }
        return Result(result, truncated)
    }
}
