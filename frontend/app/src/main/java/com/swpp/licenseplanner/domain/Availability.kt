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

    companion object {
        fun of(dayOfWeek: java.time.DayOfWeek): Weekday = entries[dayOfWeek.value - 1]
    }
}

/**
 * One weekday in the editor: the visible primary window (start and duration, possibly ending the
 * next day) plus [extras], windows this simple editor does not show but must keep. Disabling the
 * primary removes only it; the extras stay.
 */
data class DayAvailability(
    val enabled: Boolean = false,
    val start: LocalTime = DEFAULT_START,
    val durationMinutes: Int = DEFAULT_DURATION_MINUTES,
    val extras: List<WindowSpec> = emptyList(),
) {
    /** Every window of this day, primary included when enabled, sorted by start. */
    fun windows(): List<WindowSpec> =
        ((if (enabled) listOf(WindowSpec(start, durationMinutes)) else emptyList()) + extras)
            .sortedWith(compareBy({ it.start }, { it.durationMinutes }))

    companion object {
        val DEFAULT_START: LocalTime = LocalTime.of(18, 0)
        const val DEFAULT_DURATION_MINUTES = 60
        const val STEP_MINUTES = 30
        const val MIN_DURATION_MINUTES = WindowSpec.MIN_DURATION_MINUTES
        const val MAX_DURATION_MINUTES = WindowSpec.MAX_DURATION_MINUTES

        /** The first window becomes the visible primary; the rest are kept as extras. */
        fun of(windows: List<WindowSpec>): DayAvailability {
            val sorted = windows.sortedWith(compareBy({ it.start }, { it.durationMinutes }))
            val primary = sorted.firstOrNull() ?: return DayAvailability()
            return DayAvailability(true, primary.start, primary.durationMinutes, sorted.drop(1))
        }
    }
}

fun defaultAvailability(): Map<Weekday, DayAvailability> = Weekday.entries.associateWith { DayAvailability() }

/** A replacement week with no windows; it differs from a missing key, which inherits the base. */
fun emptyWeek(): Map<Weekday, DayAvailability> = defaultAvailability()

/** The editor days of one week as resolver windows; days without windows are omitted. */
fun Map<Weekday, DayAvailability>.toWeekWindows(): WeekWindows =
    Weekday.entries.mapNotNull { day -> this[day]?.windows()?.takeIf { it.isNotEmpty() }?.let { day to it } }.toMap()

/** Resolver windows as editor days for all seven weekdays. */
fun WeekWindows.toEditorDays(): Map<Weekday, DayAvailability> =
    Weekday.entries.associateWith { day -> DayAvailability.of(this[day].orEmpty()) }
