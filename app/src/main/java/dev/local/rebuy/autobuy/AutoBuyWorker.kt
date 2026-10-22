package dev.local.rebuy.autobuy

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dev.local.rebuy.RebuyApplication
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/** A stale worker can only explain the block. It never launches Coupang or marks a purchase. */
class AutoBuyWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val container = (applicationContext as RebuyApplication).container
        val store = AutoBuyStore(applicationContext)
        val configured = store.profiles.first().associateBy { it.itemId }
        for (item in container.repository.getDueItems(LocalDate.now().toEpochDay())) {
            val profile = configured[item.id] ?: continue
            store.recordBlocked(item, profile, AutoBuyPolicy.PURCHASE_BLOCKED_MESSAGE)
        }
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Log.e("AutoBuyWorker", "구매 차단 상태를 기록하지 못했습니다. 구매는 실행하지 않았습니다.", error)
        Result.failure()
    }
}
