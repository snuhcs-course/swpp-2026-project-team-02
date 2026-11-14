package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.CalendarOccurrence
import com.swpp.licenseplanner.data.model.ScheduleRow
import java.time.LocalDate
import java.security.MessageDigest

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
    /** Length-prefixed fields prevent ambiguous concatenation; raw occurrence keys stay transient. */
    fun keepDigest(ordinal: Int, conflict: CalendarConflict): String {
        val fields = listOf(ordinal.toString(), conflict.target.scheduleId.orEmpty(),
            WireDateTime.format(conflict.target.interval.start), WireDateTime.format(conflict.target.interval.end),
            conflict.occurrence.key)
        val bytes = fields.joinToString("") { "${it.length}:$it" }.encodeToByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun unacknowledged(plan: SessionPlan, result: CalendarConflictResult): CalendarConflictResult =
        CalendarConflictResult(result.matches.filter { match ->
            keepDigest(plan.ordinal, match) !in plan.keptConflicts[match.target.scheduleId].orEmpty()
        })

    fun acknowledge(plan: SessionPlan, conflicts: CalendarConflictResult): SessionPlan {
        val additions = conflicts.matches.groupBy { it.target.scheduleId }.mapNotNull { (id, matches) ->
            id?.takeIf { plan.isStudy(it) }?.let {
                it to (plan.keptConflicts[it].orEmpty() + matches.map { match -> keepDigest(plan.ordinal, match) })
            }
        }.toMap()
        return plan.copy(keptConflicts = plan.keptConflicts + additions)
    }

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
