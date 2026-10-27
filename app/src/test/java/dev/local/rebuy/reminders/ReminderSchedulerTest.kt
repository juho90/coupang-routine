package dev.local.rebuy.reminders

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.local.rebuy.data.AppDatabase
import dev.local.rebuy.data.RoutineItem
import dev.local.rebuy.data.RoutineRepository
import dev.local.rebuy.settings.ReminderSettings
import dev.local.rebuy.settings.SettingsRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReminderSchedulerTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var repository: RoutineRepository
    private lateinit var settings: SettingsRepository
    private lateinit var scheduler: ReminderScheduler
    private lateinit var manager: NotificationManager
    private lateinit var ledger: ReminderLedger

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoutineRepository(database)
        settings = SettingsRepository(context)
        settings.update(enabled = true, hour = 9, minute = 0)
        scheduler = ReminderScheduler(context, repository, settings)
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        shadowOf(manager).setNotificationsEnabled(true)
        ledger = ReminderLedger(context)
        for (id in ledger.knownIds()) ledger.forget(id)
    }

    @After fun tearDown() {
        WorkManager.getInstance(context).cancelAllWork().result.get()
        WorkManagerTestInitHelper.closeWorkDatabase()
        database.close()
    }

    @Test fun deliveredCycleIsDurableAcrossSchedulerInstances() = runBlocking {
        val item = save()
        assertEquals(DeliveryOutcome.DELIVERED, scheduler.deliver(item.id, item.cycleId))
        assertEquals(item.cycleId, ledger.deliveredCycle(item.id))
        assertEquals(DeliveryOutcome.ALREADY_DELIVERED, scheduler.deliver(item.id, item.cycleId))
        val recreated = ReminderScheduler(context, repository, SettingsRepository(context))
        assertEquals(DeliveryOutcome.ALREADY_DELIVERED, recreated.deliver(item.id, item.cycleId))
        assertEquals(1, shadowOf(manager).size())
        assertTrue(repository.history.first().isEmpty())
    }

    @Test fun blockedNotificationDoesNotConsumeCycleAndCanBeRetried() = runBlocking {
        val item = save()
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(DeliveryOutcome.SKIPPED, scheduler.deliver(item.id, item.cycleId))
        assertNull(ledger.deliveredCycle(item.id))
        assertEquals(0, shadowOf(manager).size())
        shadowOf(manager).setNotificationsEnabled(true)
        assertEquals(DeliveryOutcome.DELIVERED, scheduler.deliver(item.id, item.cycleId))
        assertEquals(item.cycleId, ledger.deliveredCycle(item.id))
    }

    @Test fun pausedArchivedStaleAndMissingItemsCannotNotify() = runBlocking {
        val paused = save()
        repository.setEnabled(paused.id, false)
        val pausedCurrent = requireNotNull(repository.getItem(paused.id))
        val archived = save()
        repository.archiveItem(archived.id)
        val archivedCurrent = requireNotNull(repository.getItem(archived.id))
        val stale = save()
        save(id = stale.id)
        assertEquals(DeliveryOutcome.SKIPPED, scheduler.deliver(paused.id, pausedCurrent.cycleId))
        assertEquals(DeliveryOutcome.SKIPPED, scheduler.deliver(archived.id, archivedCurrent.cycleId))
        assertEquals(DeliveryOutcome.SKIPPED, scheduler.deliver(stale.id, stale.cycleId))
        assertEquals(DeliveryOutcome.SKIPPED, scheduler.deliver("missing", "missing-cycle"))
        assertEquals(0, shadowOf(manager).size())
        assertNull(ledger.deliveredCycle(paused.id))
        assertNull(ledger.deliveredCycle(archived.id))
        assertNull(ledger.deliveredCycle(stale.id))
    }

    @Test fun earlyWorkerDoesNotConsumeFutureCycle() = runBlocking {
        val item = save(due = LocalDate.now().plusDays(1))
        assertEquals(DeliveryOutcome.NOT_YET_DUE, scheduler.deliver(item.id, item.cycleId))
        assertNull(ledger.deliveredCycle(item.id))
        assertEquals(0, shadowOf(manager).size())
    }

    @Test fun unchangedScheduleKeepsWorkButEditedCycleReplacesItAndClearsOldNotification() = runBlocking {
        val item = save(due = LocalDate.of(2099, 1, 1))
        scheduler.schedule(item.id)
        val initial = pendingWork(item.id)
        scheduler.schedule(item.id)
        assertEquals(initial.id, pendingWork(item.id).id)
        assertTrue(ReminderNotifications(context).show(item))
        assertEquals(1, shadowOf(manager).size())

        val edited = save(id = item.id, due = LocalDate.of(2099, 2, 1))
        scheduler.schedule(item.id)
        val replacement = pendingWork(item.id)
        assertNotEquals(initial.id, replacement.id)
        val oldWork = WorkManager.getInstance(context).getWorkInfoById(initial.id).get()
        assertTrue(oldWork == null || oldWork.state == WorkInfo.State.CANCELLED)
        assertTrue(requireNotNull(ledger.scheduleSignature(item.id)).startsWith("${edited.cycleId}|"))
        assertEquals(0, shadowOf(manager).size())
    }

    @Test fun pauseArchiveAndDisablingSettingsCancelQueuedWorkAndPostedNotifications() = runBlocking {
        val paused = save(due = LocalDate.of(2099, 1, 1))
        scheduler.schedule(paused.id)
        ReminderNotifications(context).show(paused)
        repository.setEnabled(paused.id, false)
        scheduler.schedule(paused.id)
        assertTrue(work(paused.id).all { it.state.isFinished })
        assertEquals(0, shadowOf(manager).size())

        val archived = save(due = LocalDate.of(2099, 1, 1))
        scheduler.schedule(archived.id)
        ReminderNotifications(context).show(archived)
        repository.archiveItem(archived.id)
        scheduler.reconcileAll()
        assertTrue(work(archived.id).all { it.state.isFinished })
        assertFalse(ledger.knownIds().contains(archived.id))
        assertEquals(0, shadowOf(manager).size())

        val disabled = save(due = LocalDate.of(2099, 1, 1))
        scheduler.schedule(disabled.id)
        ReminderNotifications(context).show(disabled)
        settings.update(enabled = false, hour = 10, minute = 45)
        scheduler.reconcileAll()
        assertTrue(work(disabled.id).all { it.state.isFinished })
        assertEquals(0, shadowOf(manager).size())
        assertEquals(ReminderSettings(false, 10, 45), SettingsRepository(context).settings.first())
    }

    @Test fun clockAdjustmentReplacesElapsedTimeWorkWithoutChangingTheCycle() = runBlocking {
        val item = save(due = LocalDate.of(2099, 1, 1))
        scheduler.schedule(item.id)
        val initial = pendingWork(item.id)
        scheduler.reconcileAll(forceReschedule = true)
        assertNotEquals(initial.id, pendingWork(item.id).id)
        assertEquals(item.cycleId, repository.getItem(item.id)?.cycleId)
    }

    @Test fun deliveredCurrentNotificationSurvivesClockAndReminderTimeChanges() = runBlocking {
        val item = save()
        assertEquals(DeliveryOutcome.DELIVERED, scheduler.deliver(item.id, item.cycleId))
        scheduler.reconcileAll(forceReschedule = true)
        assertEquals(1, shadowOf(manager).size())
        assertNotNull(shadowOf(manager).getNotification(item.id, 1))
        assertEquals(item.cycleId, ledger.deliveredCycle(item.id))

        settings.update(enabled = true, hour = 18, minute = 45)
        scheduler.reconcileAll()
        assertEquals(1, shadowOf(manager).size())
        assertNotNull(shadowOf(manager).getNotification(item.id, 1))
        assertEquals(DeliveryOutcome.ALREADY_DELIVERED, scheduler.deliver(item.id, item.cycleId))
        assertEquals(item.cycleId, ledger.deliveredCycle(item.id))
    }

    @Test fun sameProductUrlStillHasDistinctPendingIntentsAndCompletionRequiresActivity() = runBlocking {
        val first = save()
        val second = save()
        val publisher = ReminderNotifications(context)
        assertTrue(publisher.show(first))
        assertTrue(publisher.show(second))
        val firstNotification = requireNotNull(shadowOf(manager).getNotification(first.id, 1))
        val secondNotification = requireNotNull(shadowOf(manager).getNotification(second.id, 1))
        assertNotEquals(firstNotification.contentIntent, secondNotification.contentIntent)
        assertNotEquals(firstNotification.contentIntent, firstNotification.actions[1].actionIntent)
        val open = shadowOf(firstNotification.contentIntent).savedIntent
        assertEquals(OpenProductActivity::class.java.name, open.component?.className)
        assertEquals(first.id, open.getStringExtra(ReminderIntents.EXTRA_ITEM_ID))
        val confirmation = shadowOf(firstNotification.actions[1].actionIntent).savedIntent
        assertEquals("dev.local.rebuy.MainActivity", confirmation.component?.className)
        assertEquals(first.cycleId, confirmation.getStringExtra(ReminderIntents.EXTRA_CYCLE_ID))
        assertTrue(repository.history.first().isEmpty())
        assertEquals(first.cycleId, repository.getItem(first.id)?.cycleId)
    }

    @Test fun invalidReminderTimeIsRejectedWithoutOverwritingSavedSettings() = runBlocking {
        settings.update(false, 18, 30)
        for ((hour, minute) in listOf(-1 to 0, 24 to 0, 9 to -1, 9 to 60)) {
            try {
                settings.update(true, hour, minute)
                fail("Invalid reminder time was accepted")
            } catch (_: IllegalArgumentException) {
                // User preferences must be written only after validation succeeds.
            }
        }
        assertEquals(ReminderSettings(false, 18, 30), settings.settings.first())
    }

    private suspend fun save(id: String? = null, due: LocalDate = LocalDate.now().minusDays(1)): RoutineItem {
        val savedId = repository.saveItem(
            id, "생수", "https://www.coupang.com/vp/products/123?itemId=456", 2, 7, due.toEpochDay(),
            purchaseOption = null, maxTotalWon = null, productVariantsJson = null,
        )
        return requireNotNull(repository.getItem(savedId))
    }

    private fun work(itemId: String): List<WorkInfo> = WorkManager.getInstance(context)
        .getWorkInfosForUniqueWork("rebuy-reminder-$itemId").get()

    private fun pendingWork(itemId: String): WorkInfo = work(itemId).single { !it.state.isFinished }

}
