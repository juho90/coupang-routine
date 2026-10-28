package dev.local.rebuy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routine_items WHERE archived = 0 ORDER BY nextPurchaseEpochDay ASC, createdAtEpochMillis ASC, id ASC")
    fun observeItems(): Flow<List<RoutineItem>>

    @Query("SELECT * FROM routine_items WHERE id = :id LIMIT 1")
    suspend fun getItem(id: String): RoutineItem?

    @Query("SELECT * FROM routine_items WHERE archived = 0 AND enabled = 1 AND nextPurchaseEpochDay <= :todayEpochDay ORDER BY nextPurchaseEpochDay ASC, id ASC")
    suspend fun getDueItems(todayEpochDay: Long): List<RoutineItem>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: RoutineItem)

    @Update
    suspend fun update(item: RoutineItem)

    @Query("DELETE FROM routine_items WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
