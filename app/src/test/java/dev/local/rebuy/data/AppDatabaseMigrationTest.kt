package dev.local.rebuy.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

/** Validates on-disk upgrades through Room v3. Short test names avoid Windows temporary-path limits. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class AppDatabaseMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "migration-${UUID.randomUUID()}.db"
    private var migrated: AppDatabase? = null

    @After fun cleanUp() {
        migrated?.close()
        context.deleteDatabase(databaseName)
    }

    @Test fun v1RowsAndHistorySurviveMigration() = runBlocking {
        createVersionOneFixture()
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build().also { migrated = it }
        assertEquals(3, database.openHelper.writableDatabase.version)
        val repository = RoutineRepository(database)
        val active = requireNotNull(repository.getItem("active-v1"))
        val paused = requireNotNull(repository.getItem("paused-v1"))
        val archived = requireNotNull(repository.getItem("archived-v1"))
        assertEquals("등록 상품", active.title)
        assertEquals(PRODUCT_URL, active.productUrl)
        assertEquals(3, active.quantity)
        assertEquals(7, active.intervalDays)
        assertEquals(20_700L, active.nextPurchaseEpochDay)
        assertEquals("cycle-active", active.cycleId)
        assertEquals(1_234L, active.createdAtEpochMillis)
        assertEquals(2_345L, active.updatedAtEpochMillis)
        assertTrue(active.enabled)
        assertFalse(active.archived)
        assertFalse(paused.enabled)
        assertFalse(paused.archived)
        assertFalse(archived.enabled)
        assertTrue(archived.archived)
        listOf(active, paused, archived).forEach { item ->
            assertNull(item.purchaseOption)
            assertNull(item.maxTotalWon)
            assertNull(item.productVariantsJson)
        }
        assertEquals(setOf("active-v1", "paused-v1"), repository.items.first().map { it.id }.toSet())
        assertEquals(listOf("active-v1"), repository.getDueItems(20_710).map { it.id })

        val expectedHistory = PurchaseRecord(
            id = "history-v1", itemId = "active-v1", cycleId = "historical-cycle",
            title = "구매 당시 상품명", productUrl = PRODUCT_URL, quantity = 2,
            scheduledEpochDay = 20_690, completedAtEpochMillis = 1_000,
        )
        assertEquals(listOf(expectedHistory), repository.history.first())
        assertEquals(-1L, database.purchaseDao().insertOnce(expectedHistory.copy(id = "duplicate-after-migration")))
        assertEquals(listOf(expectedHistory), repository.history.first())

        repository.saveItem(active.id, active.title, active.productUrl, active.quantity, active.intervalDays,
            active.nextPurchaseEpochDay, purchaseOption = "600ml,2개", maxTotalWon = 75_000, productVariantsJson = null)
        val updated = requireNotNull(repository.getItem(active.id))
        assertEquals("600ml,2개", updated.purchaseOption)
        assertEquals(75_000L, updated.maxTotalWon)
        assertNotEquals(active.cycleId, updated.cycleId)
        assertTrue(updated.enabled)
        assertEquals(listOf(expectedHistory), repository.history.first())
    }

    @Test fun v2ConditionsAndHistorySurviveMigration() = runBlocking {
        createVersionOneFixture()
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { fixture ->
            fixture.execSQL("ALTER TABLE routine_items ADD COLUMN purchaseOption TEXT DEFAULT NULL")
            fixture.execSQL("ALTER TABLE routine_items ADD COLUMN maxTotalWon INTEGER DEFAULT NULL")
            fixture.execSQL("UPDATE routine_items SET purchaseOption = '600ml,2개', maxTotalWon = 75000 WHERE id = 'active-v1'")
            fixture.version = 2
        }
        val database = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
            .addMigrations(AppDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build().also { migrated = it }
        assertEquals(3, database.openHelper.writableDatabase.version)
        val item = requireNotNull(database.routineDao().getItem("active-v1"))
        assertEquals("600ml,2개", item.purchaseOption)
        assertEquals(75_000L, item.maxTotalWon)
        assertNull(item.productVariantsJson)
        assertEquals("cycle-active", item.cycleId)
        assertEquals(3, item.quantity)
        assertEquals(1, database.purchaseDao().observeHistory().first().size)
    }
    private fun createVersionOneFixture() {
        val file = context.getDatabasePath(databaseName)
        check(file.parentFile?.mkdirs() == true || file.parentFile?.isDirectory == true)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            // Kept identical to the checked-in v1 schema. New condition columns are absent.
            database.execSQL("CREATE TABLE routine_items (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `productUrl` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `intervalDays` INTEGER NOT NULL, `nextPurchaseEpochDay` INTEGER NOT NULL, `cycleId` TEXT NOT NULL, `enabled` INTEGER NOT NULL, `archived` INTEGER NOT NULL, `createdAtEpochMillis` INTEGER NOT NULL, `updatedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            database.execSQL("CREATE INDEX index_routine_items_archived_enabled_nextPurchaseEpochDay ON routine_items (archived, enabled, nextPurchaseEpochDay)")
            database.execSQL("CREATE TABLE purchase_records (`id` TEXT NOT NULL, `itemId` TEXT NOT NULL, `cycleId` TEXT NOT NULL, `title` TEXT NOT NULL, `productUrl` TEXT NOT NULL, `quantity` INTEGER NOT NULL, `scheduledEpochDay` INTEGER NOT NULL, `completedAtEpochMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))")
            database.execSQL("CREATE UNIQUE INDEX index_purchase_records_itemId_cycleId ON purchase_records (itemId, cycleId)")
            database.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            database.execSQL("INSERT INTO room_master_table (id, identity_hash) VALUES (42, 'ccf4bc5bf44803c40ca6f24437a7baf6')")
            listOf(
                arrayOf<Any>("active-v1", "등록 상품", PRODUCT_URL, 3, 7, 20_700L, "cycle-active", 1, 0, 1_234L, 2_345L),
                arrayOf<Any>("paused-v1", "중지 상품", PRODUCT_URL, 2, 14, 20_701L, "cycle-paused", 0, 0, 1_235L, 2_346L),
                arrayOf<Any>("archived-v1", "보관 상품", PRODUCT_URL, 1, 30, 20_702L, "cycle-archived", 0, 1, 1_236L, 2_347L),
            ).forEach { values -> database.execSQL("INSERT INTO routine_items VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", values) }
            database.execSQL("INSERT INTO purchase_records VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("history-v1", "active-v1", "historical-cycle", "구매 당시 상품명", PRODUCT_URL, 2, 20_690L, 1_000L))
            database.version = 1
        }
    }

    private companion object {
        const val PRODUCT_URL = "https://www.coupang.com/vp/products/123?itemId=456&vendorItemId=789"
    }
}
