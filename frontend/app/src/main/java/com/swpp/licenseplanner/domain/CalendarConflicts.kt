package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.CalendarOccurrence
import com.swpp.licenseplanner.data.model.ScheduleRow
import java.time.LocalDate

/** A dated availability window or an existing study session. */
data class ConflictTarget(val interval: StudyInterval, val weekday: Weekday, val scheduleId: String? = null)

data class CalendarConflict(val target: ConflictTarget, val occurrence: CalendarOccurrence)

data class CalendarConflictResult(val matches: List<CalendarConflict>) {
    val recurringWeekdays: Set<Weekday> get() = matches
        .filter { it.occurrence.seriesKey != null }
        .groupBy { it.occurrence.seriesKey to it.target.weekday }
        .filterValues { group -> group.map { it.occurrence.key }.distinct().size >= 2 }
        .keys.map { it.second }.toSet()
}

/** Pure half-open comparison shared by creation and saved-plan review. */
object CalendarConflicts {
    fun windows(availability: StudyAvailability, start: LocalDate, exam: LocalDate): List<ConflictTarget> {
        val from = start.atStartOfDay()
        val until = exam.atStartOfDay()
        return generateSequence(start.minusDays(1)) { it.plusDays(1) }.takeWhile { it < exam }
            .flatMap { origin ->
                availability.windowsStarting(origin).asSequence().map { window ->
                    val interval = window.intervalOn(origin)
                    ConflictTarget(StudyInterval(maxOf(from, interval.start), minOf(until, interval.end)), Weekday.of(origin.dayOfWeek))
                }
            }.filter { it.interval.start < it.interval.end }.distinct().toList()
    }

    fun sessions(rows: List<ScheduleRow>): List<ConflictTarget> = CalendarExport.studyRows(rows).mapNotNull { row ->
        val start = WireDateTime.parseOrNull(row.startDate) ?: return@mapNotNull null
        val end = WireDateTime.parseOrNull(row.endDate) ?: return@mapNotNull null
        if (end <= start) return@mapNotNull null
        ConflictTarget(StudyInterval(start, end), Weekday.of(start.dayOfWeek), row.scheduleId)
    }

    fun compare(targets: List<ConflictTarget>, occurrences: List<CalendarOccurrence>): CalendarConflictResult =
        CalendarConflictResult(targets.distinct().flatMap { target ->
            occurrences.distinctBy { it.key }.filter { target.interval.overlaps(it.start, it.end) }
                .map { CalendarConflict(target, it) }
        })
}
