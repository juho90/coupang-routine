package dev.local.rebuy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PurchaseDao {
    @Query("SELECT * FROM purchase_records ORDER BY completedAtEpochMillis DESC, id DESC")
    fun observeLogs(): Flow<List<PurchaseRecord>>

    @Query("SELECT * FROM purchase_records WHERE itemId = :itemId AND cycleId = :cycleId LIMIT 1")
    suspend fun getForCycle(itemId: String, cycleId: String): PurchaseRecord?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOnce(record: PurchaseRecord): Long
}
