package dev.local.rebuy.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [RoutineItem::class, PurchaseRecord::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao
    abstract fun purchaseDao(): PurchaseDao
}
