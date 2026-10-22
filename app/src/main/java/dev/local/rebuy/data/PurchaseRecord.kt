package dev.local.rebuy.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/** Snapshots survive item edits and archival. There is deliberately no cascading delete. */
@Entity(
    tableName = "purchase_records",
    indices = [Index(value = ["itemId", "cycleId"], unique = true)],
)
data class PurchaseRecord(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val itemId: String,
    val cycleId: String,
    val title: String,
    val productUrl: String,
    val quantity: Int,
    val scheduledEpochDay: Long,
    val completedAtEpochMillis: Long,
)
