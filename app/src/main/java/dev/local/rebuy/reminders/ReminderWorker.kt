package dev.local.rebuy.reminders

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.local.rebuy.RebuyApplication
import kotlinx.coroutines.CancellationException

class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val itemId = inputData.getString(ReminderIntents.EXTRA_ITEM_ID) ?: return Result.failure()
        val cycleId = inputData.getString(ReminderIntents.EXTRA_CYCLE_ID) ?: return Result.failure()
        return try {
            val scheduler = (applicationContext as RebuyApplication).container.scheduler
            when (scheduler.deliver(itemId, cycleId)) {
                DeliveryOutcome.NOT_YET_DUE -> Result.retry()
                else -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e("ReminderWorker", "구매 알림을 준비하지 못했습니다. 다시 시도합니다.", error)
            Result.retry()
        }
    }
}
