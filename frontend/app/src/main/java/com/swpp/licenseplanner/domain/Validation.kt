package com.swpp.licenseplanner.domain

import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** A valid same-day study window. */
data class StudyWindow(val start: LocalTime, val end: LocalTime)

/** Date and availability rules for FLOW-02 and FLOW-06. */
object Validation {
    private const val MINUTES_PER_DAY = 24 * 60

    /** Days between preparation start and exam date. */
    fun preparationDays(start: LocalDate, exam: LocalDate): Long = ChronoUnit.DAYS.between(start, exam)

    /** Null when valid; otherwise a user-facing message. */
    fun datesError(start: LocalDate?, exam: LocalDate?): String? = when {
        start == null -> "준비 시작일을 선택해 주세요."
        exam == null -> "시험일을 선택해 주세요."
        !exam.isAfter(start) -> "시험일은 준비 시작일보다 뒤여야 해요."
        else -> null
    }

    /**
     * The window for an enabled day, or null when start + duration does not end on the
     * same day (the latest end is 23:59; the backend rejects 24:00).
     */
    fun window(day: DayAvailability): StudyWindow? {
        if (day.durationMinutes <= 0) return null
        val endMinutes = day.start.hour * 60 + day.start.minute + day.durationMinutes
        if (endMinutes >= MINUTES_PER_DAY) return null
        return StudyWindow(day.start, LocalTime.of(endMinutes / 60, endMinutes % 60))
    }

    fun windowError(day: DayAvailability): String? =
        if (day.enabled && window(day) == null) "끝나는 시간이 자정을 넘어요. 시작 시간이나 시간을 줄여 주세요." else null

    /** Null when the availability can be submitted; otherwise the reason it cannot. */
    fun availabilityError(availability: Map<Weekday, DayAvailability>): String? = when {
        availability.values.none { it.enabled } -> "공부할 요일을 하나 이상 선택해 주세요."
        availability.values.any { windowError(it) != null } -> "자정을 넘는 시간이 있어요."
        else -> null
    }
}
