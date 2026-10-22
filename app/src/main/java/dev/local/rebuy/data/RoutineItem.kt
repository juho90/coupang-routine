package dev.local.rebuy.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "routine_items",
    indices = [Index(value = ["archived", "enabled", "nextPurchaseEpochDay"])],
)
data class RoutineItem(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val productUrl: String,
    val quantity: Int,
    val intervalDays: Int,
    val nextPurchaseEpochDay: Long,
    val cycleId: String = UUID.randomUUID().toString(),
    val enabled: Boolean = true,
    val archived: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
)
