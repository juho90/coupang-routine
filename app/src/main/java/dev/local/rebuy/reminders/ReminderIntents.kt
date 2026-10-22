package dev.local.rebuy.reminders

import android.net.Uri

object ReminderIntents {
    const val EXTRA_ITEM_ID = "item_id"
    const val EXTRA_CYCLE_ID = "cycle_id"

    internal fun identity(action: String, itemId: String, cycleId: String): Uri = Uri.Builder()
        .scheme("rebuy")
        .authority("reminders")
        .appendPath(action)
        .appendPath(itemId)
        .appendPath(cycleId)
        .build()
}
