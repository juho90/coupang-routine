package dev.local.rebuy.reminders

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Epoch days remain calendar dates; the current device time zone supplies wall-clock time. */
internal object ReminderTime {
    fun instant(epochDay: Long, hour: Int, minute: Int, zone: ZoneId): Instant =
        LocalDate.ofEpochDay(epochDay)
            .atTime(LocalTime.of(hour, minute))
            .atZone(zone)
            .toInstant()

    fun delayMillis(
        epochDay: Long,
        hour: Int,
        minute: Int,
        zone: ZoneId,
        now: Instant,
    ): Long = Duration.between(now, instant(epochDay, hour, minute, zone))
        .toMillis()
        .coerceAtLeast(0)
}
