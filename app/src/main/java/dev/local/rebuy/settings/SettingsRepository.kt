package dev.local.rebuy.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private val Context.reminderSettingsStore by preferencesDataStore(name = "reminder_settings")

data class ReminderSettings(
    val notificationsEnabled: Boolean = true,
    val hour: Int = 9,
    val minute: Int = 0,
)

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.reminderSettingsStore

    val settings: Flow<ReminderSettings> = store.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            ReminderSettings(
                notificationsEnabled = preferences[ENABLED] ?: true,
                hour = (preferences[HOUR] ?: 9).coerceIn(0, 23),
                minute = (preferences[MINUTE] ?: 0).coerceIn(0, 59),
            )
        }
        .distinctUntilChanged()

    suspend fun update(enabled: Boolean, hour: Int, minute: Int) {
        require(hour in 0..23) { "알림 시각은 0~23시여야 합니다." }
        require(minute in 0..59) { "알림 시각은 0~59분이어야 합니다." }
        store.edit { preferences ->
            preferences[ENABLED] = enabled
            preferences[HOUR] = hour
            preferences[MINUTE] = minute
        }
    }

    private companion object {
        val ENABLED = booleanPreferencesKey("notifications_enabled")
        val HOUR = intPreferencesKey("reminder_hour")
        val MINUTE = intPreferencesKey("reminder_minute")
    }
}
