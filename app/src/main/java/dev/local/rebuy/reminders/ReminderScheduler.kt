package dev.local.rebuy.reminders

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dev.local.rebuy.data.RoutineRepository
import dev.local.rebuy.domain.CoupangUrl
import dev.local.rebuy.settings.ReminderSettings
import dev.local.rebuy.settings.SettingsRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** WorkManager supplies durable, inexact reminders without exact-alarm permission. */
class ReminderScheduler(
    context: Context,
    private val repository: RoutineRepository,
    private val settings: SettingsRepository,
) {
    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val ledger = ReminderLedger(appContext)
    private val notifications = ReminderNotifications(appContext)
    private val mutex = Mutex()

    fun start() {
        notifications.createChannel()
        val periodic = PeriodicWorkRequestBuilder<ReconcileWorker>(12, TimeUnit.HOURS)
            .setInitialDelay(12, TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic,
        )
        enqueueReconciliation()
    }

    fun enqueueReconciliation(forceReschedule: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<ReconcileWorker>()
            .setInputData(workDataOf(FORCE_RESCHEDULE to forceReschedule))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        val name = if (forceReschedule) "$RECONCILE_WORK_NAME-clock" else RECONCILE_WORK_NAME
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request)
    }

    suspend fun reconcileAll(forceReschedule: Boolean = false) = mutex.withLock {
        val items = repository.items.first()
        val activeIds = items.mapTo(mutableSetOf()) { it.id }
        for (staleId in ledger.knownIds() - activeIds) {
            cancelWork(staleId)
            notifications.cancel(staleId)
            ledger.forget(staleId)
        }
        val preferences = settings.settings.first()
        for (item in items) {
            // WorkManager initial delays use elapsed time. A wall-clock adjustment must
            // replace even an otherwise identical date/time signature.
            if (forceReschedule) ledger.clearSchedule(item.id)
            scheduleLocked(item.id, preferences)
        }
    }

    suspend fun schedule(itemId: String) = mutex.withLock {
        scheduleLocked(itemId, settings.settings.first())
    }

    fun cancelNotification(itemId: String) = notifications.cancel(itemId)

    private suspend fun scheduleLocked(itemId: String, preferences: ReminderSettings) {
        val item = repository.getItem(itemId)
        if (item == null || item.archived) {
            cancelWork(itemId)
            notifications.cancel(itemId)
            ledger.forget(itemId)
            return
        }
        if (!item.enabled || !preferences.notificationsEnabled) {
            cancelWork(itemId)
            notifications.cancel(itemId)
            ledger.clearSchedule(itemId)
            return
        }
        val zone = ZoneId.systemDefault()
        val signature = listOf(
            item.cycleId,
            item.nextPurchaseEpochDay,
            preferences.hour,
            preferences.minute,
            zone.id,
        ).joinToString("|")
        val previousSignature = ledger.scheduleSignature(itemId)
        val deliveredCycle = ledger.deliveredCycle(itemId)
        if (deliveredCycle == item.cycleId) {
            // A clock or reminder-time change only affects future delivery. Keep the
            // unfinished current notification visible and preserve its delivery marker.
            cancelWork(itemId)
            ledger.rememberSchedule(itemId, signature)
            return
        }
        if (previousSignature != signature || (deliveredCycle != null && deliveredCycle != item.cycleId)) {
            notifications.cancel(itemId)
        }

        val delay = ReminderTime.delayMillis(
            item.nextPurchaseEpochDay,
            preferences.hour,
            preferences.minute,
            zone,
            Instant.now(),
        )
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(
                workDataOf(
                    ReminderIntents.EXTRA_ITEM_ID to item.id,
                    ReminderIntents.EXTRA_CYCLE_ID to item.cycleId,
                ),
            )
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(REMINDER_TAG)
            .build()
        // Reconciliation must not restart an unchanged running worker or postpone its delay.
        val policy = if (previousSignature == signature) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        val operation = workManager.enqueueUniqueWork(itemWorkName(itemId), policy, request)
        withContext(Dispatchers.IO) { operation.result.get() }
        ledger.rememberSchedule(itemId, signature)
    }

    internal suspend fun deliver(itemId: String, expectedCycleId: String): DeliveryOutcome = mutex.withLock {
        val item = repository.getItem(itemId) ?: return@withLock DeliveryOutcome.SKIPPED
        val preferences = settings.settings.first()
        if (item.archived || !item.enabled || !preferences.notificationsEnabled || item.cycleId != expectedCycleId) {
            return@withLock DeliveryOutcome.SKIPPED
        }
        if (ledger.deliveredCycle(itemId) == item.cycleId) return@withLock DeliveryOutcome.ALREADY_DELIVERED
        val zone = ZoneId.systemDefault()
        if (item.nextPurchaseEpochDay > LocalDate.now(zone).toEpochDay()) return@withLock DeliveryOutcome.NOT_YET_DUE
        if (Instant.now().isBefore(ReminderTime.instant(item.nextPurchaseEpochDay, preferences.hour, preferences.minute, zone))) {
            return@withLock DeliveryOutcome.NOT_YET_DUE
        }
        if (runCatching { CoupangUrl.normalize(item.productUrl) }.getOrNull() == null) {
            return@withLock DeliveryOutcome.SKIPPED
        }
        if (!notifications.show(item)) {
            // Do not persist a marker when permission/channel settings prevented delivery.
            // App resume and periodic reconciliation can enqueue the same cycle again later.
            return@withLock DeliveryOutcome.SKIPPED
        }
        // A crash between notify and this commit may repost; the stable tag/id replaces the
        // existing notification and setOnlyAlertOnce avoids another alert while it remains.
        ledger.recordDelivered(itemId, item.cycleId)
        DeliveryOutcome.DELIVERED
    }

    private suspend fun cancelWork(itemId: String) {
        val operation = workManager.cancelUniqueWork(itemWorkName(itemId))
        withContext(Dispatchers.IO) { operation.result.get() }
    }

    private fun itemWorkName(itemId: String) = "rebuy-reminder-$itemId"

    companion object {
        internal const val FORCE_RESCHEDULE = "force_reschedule"
        const val PERIODIC_WORK_NAME = "rebuy-reconcile-periodic"
        const val RECONCILE_WORK_NAME = "rebuy-reconcile-now"
        const val REMINDER_TAG = "rebuy-item-reminder"
    }
}

internal enum class DeliveryOutcome { DELIVERED, ALREADY_DELIVERED, SKIPPED, NOT_YET_DUE }
