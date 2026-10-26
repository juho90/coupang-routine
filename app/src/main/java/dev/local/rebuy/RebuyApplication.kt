package dev.local.rebuy

import android.app.Application
import android.content.Context
import androidx.room.Room
import dev.local.rebuy.data.AppDatabase
import dev.local.rebuy.data.RoutineRepository
import dev.local.rebuy.reminders.ReminderScheduler
import dev.local.rebuy.settings.SettingsRepository
import dev.local.rebuy.autobuy.AutoBuyStore
import dev.local.rebuy.autobuy.AutoBuyScheduler

class RebuyApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        container.scheduler.start()
        container.autoBuyScheduler.schedule()
    }
}

class AppContainer(context: Context) {
    val database = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "rebuy.db")
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()
    val repository = RoutineRepository(database)
    val settings = SettingsRepository(context.applicationContext)
    val scheduler = ReminderScheduler(context.applicationContext, repository, settings)
    val autoBuyStore = AutoBuyStore(context.applicationContext)
    val autoBuyScheduler = AutoBuyScheduler(context.applicationContext)
}
