package dev.local.rebuy.cart

import dev.local.rebuy.data.RoutineItem
import org.junit.Assert.assertEquals
import org.junit.Test

class CartBatchSelectionTest {
    private val today = 20_000L
    private fun item(id: String, day: Long, enabled: Boolean = true, archived: Boolean = false) = RoutineItem(
        id = id, title = id, productUrl = "https://www.coupang.com/vp/products/1?itemId=11&vendorItemId=12",
        quantity = 1, intervalDays = 30, nextPurchaseEpochDay = day, enabled = enabled, archived = archived,
    )

    @Test fun dueIncludesTodayAndOverdueOnlyAndExcludesPausedAndArchived() {
        val items = listOf(item("future", today + 1), item("today", today), item("overdue", today - 2),
            item("paused", today - 1, enabled = false), item("archived", today - 1, archived = true))
        assertEquals(listOf("overdue", "today"), CartBatchSelection.select(items, today, CartBatchScope.DUE).map { it.id })
    }

    @Test fun allActiveRequiresExplicitScopeAndIncludesFuture() {
        val items = listOf(item("future", today + 1), item("today", today), item("paused", today, enabled = false))
        assertEquals(listOf("today", "future"), CartBatchSelection.select(items, today, CartBatchScope.ALL_ACTIVE).map { it.id })
    }

    @Test fun selectionReevaluatesWhenDateOrRegistrationChanges() {
        val future = item("future", today + 1)
        assertEquals(emptyList<String>(), CartBatchSelection.select(listOf(future), today, CartBatchScope.DUE).map { it.id })
        assertEquals(listOf("future"), CartBatchSelection.select(listOf(future), today + 1, CartBatchScope.DUE).map { it.id })
        assertEquals(emptyList<String>(), CartBatchSelection.select(listOf(future.copy(enabled = false)), today + 1, CartBatchScope.DUE).map { it.id })
    }

    @Test fun sameDateHasStableTitleAndIdOrderWithoutChangingSource() {
        val items = listOf(item("b", today), item("a", today), item("c", today).copy(title = "a"))
        assertEquals(listOf("a", "c", "b"), CartBatchSelection.select(items, today, CartBatchScope.DUE).map { it.id })
        assertEquals(listOf("b", "a", "c"), items.map { it.id })
    }
}
