package dev.local.rebuy.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.local.rebuy.RebuyApplication

/** System clock/time-zone changes require wall-clock dates to be mapped to instants again. */
class ReminderTimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_BOOT_COMPLETED)) {
            (context.applicationContext as RebuyApplication).container.scheduler.enqueueReconciliation(forceReschedule = true)
        }
    }
}
