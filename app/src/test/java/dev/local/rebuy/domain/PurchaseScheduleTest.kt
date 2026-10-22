package dev.local.rebuy.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PurchaseScheduleTest {
    @Test
    fun calculatesCalendarDaysAcrossLeapDayAndYearBoundary() {
        assertEquals(day("2028-03-01"), PurchaseSchedule.nextPurchaseEpochDay(day("2028-02-28"), 2))
        assertEquals(day("2027-01-06"), PurchaseSchedule.nextPurchaseEpochDay(day("2026-12-30"), 7))
    }

    @Test
    fun usesPurchaseDateWithoutAccumulatingMissedCycles() {
        assertEquals(day("2026-11-20"), PurchaseSchedule.nextPurchaseEpochDay(day("2026-10-21"), 30))
    }

    @Test
    fun automaticAdvanceCanCrossManualInputUpperBound() {
        assertEquals(day("2101-01-07"), PurchaseSchedule.nextPurchaseEpochDay(day("2100-12-31"), 7))
    }

    @Test
    fun rejectsInvalidPeriodsAndUnreasonableDates() {
        listOf(-1, 0, 366).forEach { interval ->
            assertThrows(IllegalArgumentException::class.java) { PurchaseSchedule.nextPurchaseEpochDay(day("2026-10-21"), interval) }
        }
        listOf(Long.MIN_VALUE, Long.MAX_VALUE, day("1999-12-31"), day("2101-01-01")).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { PurchaseSchedule.requireValidDate(value) }
        }
        assertEquals(LocalDate.parse("2000-01-01"), PurchaseSchedule.requireValidDate(day("2000-01-01")))
        assertEquals(LocalDate.parse("2100-12-31"), PurchaseSchedule.requireValidDate(day("2100-12-31")))
    }

    private fun day(value: String) = LocalDate.parse(value).toEpochDay()
}
