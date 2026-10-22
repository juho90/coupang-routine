package dev.local.rebuy.reminders

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.local.rebuy.RebuyApplication
import kotlinx.coroutines.CancellationException

class ReconcileWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        (applicationContext as RebuyApplication).container.scheduler.reconcileAll(
            forceReschedule = inputData.getBoolean(ReminderScheduler.FORCE_RESCHEDULE, false),
        )
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.e("ReconcileWorker", "구매 알림 일정을 다시 맞추지 못했습니다. 다시 시도합니다.", error)
        Result.retry()
    }
}
