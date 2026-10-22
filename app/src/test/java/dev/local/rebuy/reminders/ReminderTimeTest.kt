package dev.local.rebuy.reminders

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderTimeTest {
    @Test fun `Korean calendar date is scheduled at the selected wall clock time`() {
        assertEquals(
            Instant.parse("2026-10-21T00:30:00Z"),
            ReminderTime.instant(LocalDate.of(2026, 10, 21).toEpochDay(), 9, 30, ZoneId.of("Asia/Seoul")),
        )
    }

    @Test fun `overdue reminders run immediately instead of overflowing delay`() {
        assertEquals(0L, ReminderTime.delayMillis(
            LocalDate.of(2026, 10, 20).toEpochDay(), 9, 0, ZoneId.of("Asia/Seoul"),
            Instant.parse("2026-10-21T00:00:00Z"),
        ))
    }

    @Test fun `a future reminder preserves minutes`() {
        assertEquals(90_000L, ReminderTime.delayMillis(
            LocalDate.of(2026, 10, 21).toEpochDay(), 9, 2, ZoneId.of("Asia/Seoul"),
            Instant.parse("2026-10-21T00:00:30Z"),
        ))
    }

    @Test fun `daylight saving gap advances to valid local time`() {
        assertEquals(
            Instant.parse("2026-03-08T07:30:00Z"),
            ReminderTime.instant(LocalDate.of(2026, 3, 8).toEpochDay(), 2, 30, ZoneId.of("America/New_York")),
        )
    }

    @Test fun `timezone changes recompute instant without changing purchase date`() {
        val date = LocalDate.of(2026, 10, 21).toEpochDay()
        assertEquals(Instant.parse("2026-10-21T16:00:00Z"),
            ReminderTime.instant(date, 9, 0, ZoneId.of("America/Los_Angeles")))
    }

    @Test fun `daylight saving overlap uses the first occurrence`() {
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"),
            ReminderTime.instant(LocalDate.of(2026, 11, 1).toEpochDay(), 1, 30, ZoneId.of("America/New_York")))
    }
}
