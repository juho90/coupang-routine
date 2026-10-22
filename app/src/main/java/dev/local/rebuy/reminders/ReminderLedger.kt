package dev.local.rebuy.reminders

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.reminderDeliveryStore by preferencesDataStore(name = "reminder_delivery")

/** Durable per-cycle delivery markers survive process death and WorkManager retries. */
internal class ReminderLedger(context: Context) {
    private val store = context.applicationContext.reminderDeliveryStore

    suspend fun knownIds(): Set<String> = store.data.first()[KNOWN_IDS].orEmpty()

    suspend fun deliveredCycle(itemId: String): String? = store.data.first()[deliveredKey(itemId)]

    suspend fun scheduleSignature(itemId: String): String? = store.data.first()[scheduleKey(itemId)]

    suspend fun rememberSchedule(itemId: String, signature: String) {
        store.edit { preferences ->
            preferences[KNOWN_IDS] = preferences[KNOWN_IDS].orEmpty() + itemId
            preferences[scheduleKey(itemId)] = signature
        }
    }

    suspend fun recordDelivered(itemId: String, cycleId: String) {
        store.edit { preferences ->
            preferences[KNOWN_IDS] = preferences[KNOWN_IDS].orEmpty() + itemId
            preferences[deliveredKey(itemId)] = cycleId
        }
    }

    suspend fun clearSchedule(itemId: String) {
        store.edit { it.remove(scheduleKey(itemId)) }
    }

    suspend fun forget(itemId: String) {
        store.edit { preferences ->
            preferences[KNOWN_IDS] = preferences[KNOWN_IDS].orEmpty() - itemId
            preferences.remove(deliveredKey(itemId))
            preferences.remove(scheduleKey(itemId))
        }
    }

    private fun deliveredKey(itemId: String) = stringPreferencesKey("delivered_$itemId")
    private fun scheduleKey(itemId: String) = stringPreferencesKey("schedule_$itemId")

    private companion object {
        val KNOWN_IDS = stringSetPreferencesKey("known_item_ids")
    }
}
