package com.swpp.licenseplanner.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters

/**
 * One study window owned by the weekday it starts on. It lasts [durationMinutes] (1–1439) and may
 * end on the next civil date; an end exactly at midnight still belongs to the start day.
 */
data class WindowSpec(val start: LocalTime, val durationMinutes: Int) {
    val end: LocalTime get() = start.plusMinutes(durationMinutes.toLong())

    /** Whether the end time is on the following date (including an end at exactly 00:00). */
    val endsNextDay: Boolean get() = start.toSecondOfDay() / 60 + durationMinutes >= MINUTES_PER_DAY

    fun intervalOn(origin: LocalDate): StudyInterval {
        val from = origin.atTime(start)
        return StudyInterval(from, from.plusMinutes(durationMinutes.toLong()))
    }

    companion object {
        const val MIN_DURATION_MINUTES = 1
        const val MAX_DURATION_MINUTES = 1439
        const val MINUTES_PER_DAY = 24 * 60

        fun isValidDuration(minutes: Int): Boolean = minutes in MIN_DURATION_MINUTES..MAX_DURATION_MINUTES
    }
}

/** A half-open absolute interval `[start, end)`. */
data class StudyInterval(val start: LocalDateTime, val end: LocalDateTime) {
    fun overlaps(from: LocalDateTime, until: LocalDateTime): Boolean = start < until && from < end
}

/** Windows for each weekday; a weekday that is absent has no windows. */
typealias WeekWindows = Map<Weekday, List<WindowSpec>>

/**
 * A recurring base week plus whole-week replacements keyed by their Monday. The same rules as the
 * backend: a window follows the week of the date it starts on, an empty replacement week has no
 * originating windows, and a missing key inherits the base.
 */
data class StudyAvailability(val base: WeekWindows, val overrides: Map<LocalDate, WeekWindows> = emptyMap()) {
    /** The weekly windows that apply to windows starting on [origin]. */
    fun weekFor(origin: LocalDate): WeekWindows = overrides[mondayOf(origin)] ?: base

    fun windowsStarting(origin: LocalDate): List<WindowSpec> =
        weekFor(origin)[Weekday.of(origin.dayOfWeek)].orEmpty()

    /** Sorted absolute intervals intersecting [day] with their full endpoints, including the previous date's spill. */
    fun windowsFor(day: LocalDate): List<StudyInterval> {
        val dayStart = day.atStartOfDay()
        val dayEnd = day.plusDays(1).atStartOfDay()
        return listOf(day.minusDays(1), day)
            .flatMap { origin -> windowsStarting(origin).map { it.intervalOn(origin) } }
            .filter { it.overlaps(dayStart, dayEnd) }
            .distinct()
            .sortedWith(compareBy({ it.start }, { it.end }))
    }

    fun hasWindows(): Boolean = base.values.any { it.isNotEmpty() } || overrides.values.any { week -> week.values.any { it.isNotEmpty() } }

    /** Weekdays with any window in the base or any replacement week. */
    fun weekdaysWithWindows(): Set<Weekday> =
        (listOf(base) + overrides.values).flatMap { week -> week.filterValues { it.isNotEmpty() }.keys }.toSet()

    /** Whether one effective interval contains the whole `[start, end)`. */
    fun fitsOneWindow(start: LocalDateTime, end: LocalDateTime): Boolean =
        windowsFor(start.toLocalDate()).any { !it.start.isAfter(start) && !end.isAfter(it.end) }

    /**
     * The first pair of overlapping effective windows, or null. The base is checked over one week
     * and the next Monday; every replacement week with the day before it and the following Monday.
     */
    fun firstOverlap(): Pair<StudyInterval, StudyInterval>? {
        StudyAvailability(base).overlapAmong((0L..7L).map { REFERENCE_MONDAY.plusDays(it) })?.let { return it }
        val origins = overrides.keys.flatMap { monday -> (-1L..7L).map { monday.plusDays(it) } }
        return if (origins.isEmpty()) null else overlapAmong(origins)
    }

    private fun overlapAmong(origins: Collection<LocalDate>): Pair<StudyInterval, StudyInterval>? {
        val spans = origins.toSet()
            .flatMap { origin -> windowsStarting(origin).map { it.intervalOn(origin) } }
            .sortedWith(compareBy({ it.start }, { it.end }))
        return spans.zipWithNext().firstOrNull { (previous, current) -> current.start < previous.end }
    }

    companion object {
        // Any Monday works as the reference week for the recurring base cycle.
        private val REFERENCE_MONDAY: LocalDate = LocalDate.of(2001, 1, 1)

        fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}
