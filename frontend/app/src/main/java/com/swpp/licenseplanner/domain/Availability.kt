package com.swpp.licenseplanner.domain

import java.time.LocalTime

/** Weekday keys used on the wire (`monday`…`sunday`) and their Korean labels. */
enum class Weekday(val key: String, val label: String) {
    MONDAY("monday", "월"),
    TUESDAY("tuesday", "화"),
    WEDNESDAY("wednesday", "수"),
    THURSDAY("thursday", "목"),
    FRIDAY("friday", "금"),
    SATURDAY("saturday", "토"),
    SUNDAY("sunday", "일"),
    ;

    val dayOfWeek: java.time.DayOfWeek get() = java.time.DayOfWeek.of(ordinal + 1)
}

/** One window per weekday entered as a start time and a duration (FLOW-06). */
data class DayAvailability(
    val enabled: Boolean = false,
    val start: LocalTime = DEFAULT_START,
    val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
) {
    companion object {
        val DEFAULT_START: LocalTime = LocalTime.of(18, 0)
        const val DEFAULT_DURATION_MINUTES = 60
        const val STEP_MINUTES = 30
        const val MIN_DURATION_MINUTES = 30
        const val MAX_DURATION_MINUTES = 12 * 60
    }
}

fun defaultAvailability(): Map<Weekday, DayAvailability> = Weekday.entries.associateWith { DayAvailability() }
