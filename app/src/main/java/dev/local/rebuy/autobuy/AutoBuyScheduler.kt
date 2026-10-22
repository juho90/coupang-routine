package dev.local.rebuy.autobuy

import android.content.Context
import androidx.work.WorkManager

/** Scheduling is intentionally inert while the user's purchase prohibition is in force. */
class AutoBuyScheduler(context: Context) {
    private val applicationContext = context.applicationContext

    fun schedule() {
        // Cancel by stable names, including work retained by an older installed build. This
        // version never enqueues a periodic or immediate worker that could open checkout.
        val manager = WorkManager.getInstance(applicationContext)
        manager.cancelUniqueWork(PERIODIC_WORK)
        manager.cancelUniqueWork(IMMEDIATE_WORK)
    }

    companion object {
        const val PERIODIC_WORK = "rebuy-auto-buy-periodic"
        const val IMMEDIATE_WORK = "rebuy-auto-buy-immediate"
    }
}
