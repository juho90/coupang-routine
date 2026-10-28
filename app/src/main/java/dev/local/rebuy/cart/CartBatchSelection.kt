package dev.local.rebuy.cart

import dev.local.rebuy.data.RoutineItem

internal enum class CartBatchScope { DUE, ALL_ACTIVE }

internal object CartBatchSelection {
    fun select(items: List<RoutineItem>, today: Long, scope: CartBatchScope): List<RoutineItem> = items
        .filter { it.enabled && !it.archived && (scope == CartBatchScope.ALL_ACTIVE || it.nextPurchaseEpochDay <= today) }
        .sortedWith(compareBy<RoutineItem> { it.nextPurchaseEpochDay }.thenBy { it.title }.thenBy { it.id })
}
