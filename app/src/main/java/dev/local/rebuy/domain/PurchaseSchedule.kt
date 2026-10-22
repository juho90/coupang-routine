package dev.local.rebuy.domain

import java.time.LocalDate

object PurchaseSchedule {
    val earliestDate: LocalDate = LocalDate.of(2000, 1, 1)
    val latestDate: LocalDate = LocalDate.of(2100, 12, 31)

    fun requireValidDate(epochDay: Long): LocalDate {
        require(epochDay in earliestDate.toEpochDay()..latestDate.toEpochDay()) {
            "구매일은 2000-01-01부터 2100-12-31 사이로 설정해 주세요."
        }
        return LocalDate.ofEpochDay(epochDay)
    }

    /**
     * Delays do not produce a backlog: the next cycle starts on the actual purchase day.
     * The form's 2100 limit applies to manual input; advancing a valid cycle can cross it.
     */
    fun nextPurchaseEpochDay(completedOnEpochDay: Long, intervalDays: Int): Long {
        require(intervalDays in 1..365) { "구매 주기는 1~365일로 입력해 주세요." }
        return LocalDate.ofEpochDay(completedOnEpochDay).plusDays(intervalDays.toLong()).toEpochDay()
    }
}
