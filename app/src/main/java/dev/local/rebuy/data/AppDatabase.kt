package dev.local.rebuy.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [RoutineItem::class, PurchaseRecord::class], version = 3, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun routineDao(): RoutineDao
    abstract fun purchaseDao(): PurchaseDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routine_items ADD COLUMN purchaseOption TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE routine_items ADD COLUMN maxTotalWon INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE routine_items ADD COLUMN productVariantsJson TEXT DEFAULT NULL")
            }
        }
    }
}
