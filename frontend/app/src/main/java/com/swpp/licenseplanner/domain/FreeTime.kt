package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.CalendarOccurrence
import com.swpp.licenseplanner.data.model.RemainingSession
import com.swpp.licenseplanner.data.model.ScheduleRow
import java.time.LocalDate
import java.time.LocalDateTime

data class FreeTimeBand(val startMinute: Int, val endMinute: Int)
data class FreeTimeSuggestion(val start: LocalDateTime, val end: LocalDateTime, val outsideWindows: Boolean)
data class PlacedSession(val sourceId: String, val row: ScheduleRow, val outsideWindows: Boolean)

/** Calendar timing stays local; only a confirmed start belongs in a move request. */
object FreeTime {
    fun band(availability: StudyAvailability): FreeTimeBand? {
        val windows = (listOf(availability.base) + availability.overrides.values).flatMap { it.values.flatten() }
        if (windows.isEmpty()) return null
        return FreeTimeBand(windows.minOf { it.start.toSecondOfDay() / 60 },
            windows.maxOf { it.start.toSecondOfDay() / 60 + it.durationMinutes })
    }

    internal fun roundUp10(value: LocalDateTime): LocalDateTime {
        val floor = value.withSecond(0).withNano(0)
        val minutes = (10 - floor.minute % 10) % 10
        return floor.plusMinutes(if (minutes == 0 && value != floor) 10 else minutes.toLong())
    }

    fun earliestStart(band: FreeTimeBand, blocked: List<StudyInterval>, lowerBound: LocalDateTime,
                      duration: Int, exam: LocalDateTime, fromDate: LocalDate, toDate: LocalDate): LocalDateTime? {
        require(duration > 0)
        var origin = fromDate.minusDays(1)
        while (origin <= toDate) {
            var start = roundUp10(maxOf(lowerBound, fromDate.atStartOfDay(),
                origin.atStartOfDay().plusMinutes(band.startMinute.toLong())))
            val end = minOf(exam, origin.atStartOfDay().plusMinutes(band.endMinute.toLong()))
            while (start.toLocalDate() <= toDate && start.plusMinutes(duration.toLong()) <= end) {
                val overlaps = blocked.filter { it.overlaps(start, start.plusMinutes(duration.toLong())) }
                if (overlaps.isEmpty()) return start
                start = roundUp10(overlaps.maxOf { it.end })
            }
            origin = origin.plusDays(1)
        }
        return null
    }

    private fun blocked(rows: List<ScheduleRow>, occurrences: List<CalendarOccurrence>): List<StudyInterval> =
        PlanGrouping.sessions(rows).map { StudyInterval(it.start, it.end) } +
            occurrences.map { StudyInterval(it.start, it.end) }

    fun suggest(availability: StudyAvailability, rows: List<ScheduleRow>, targetId: String,
                occurrences: List<CalendarOccurrence>, preparationStart: LocalDateTime,
                examDate: LocalDateTime, now: LocalDateTime, limit: Int = 3): List<FreeTimeSuggestion> {
        val band = band(availability) ?: return emptyList()
        val target = PlanGrouping.sessions(rows).singleOrNull { it.scheduleId == targetId } ?: return emptyList()
        val lower = maxOf(roundUp10(now), preparationStart)
        val blocked = blocked(rows, occurrences)
        val result = mutableListOf<FreeTimeSuggestion>()
        var date = lower.toLocalDate()
        while (date <= examDate.toLocalDate() && result.size < limit) {
            earliestStart(band, blocked, lower, target.durationMinutes.toInt(), examDate, date, date)?.let { start ->
                val end = start.plusMinutes(target.durationMinutes)
                result += FreeTimeSuggestion(start, end, !availability.fitsOneWindow(start, end))
            }
            date = date.plusDays(1)
        }
        return result
    }

    fun placeBatch(availability: StudyAvailability, rows: List<ScheduleRow>, instructions: List<RemainingSession>,
                   occurrences: List<CalendarOccurrence>, preparationStart: LocalDateTime,
                   examDate: LocalDateTime, replanFrom: LocalDateTime, now: LocalDateTime): List<PlacedSession>? {
        val band = band(availability) ?: return null
        val sources = instructions.map { it.scheduleId }.toSet()
        val blocked = blocked(rows.filterNot { it.scheduleId in sources }, occurrences).toMutableList()
        val lower = roundUp10(maxOf(preparationStart, replanFrom, now.plusMinutes(1)))
        val originals = rows.associateBy { it.scheduleId }
        val ordered = instructions.sortedWith(compareBy<RemainingSession> { originals.getValue(it.scheduleId).startDate }
            .thenBy { originals.getValue(it.scheduleId).endDate }.thenBy { it.scheduleId.removePrefix("study-").toLong() })
        return ordered.map { instruction ->
            val start = earliestStart(band, blocked, lower, instruction.remainingMinutes, examDate,
                lower.toLocalDate(), examDate.toLocalDate()) ?: return null
            val end = start.plusMinutes(instruction.remainingMinutes.toLong())
            blocked += StudyInterval(start, end)
            PlacedSession(instruction.scheduleId, originals.getValue(instruction.scheduleId).copy(
                scheduleId = instruction.replacementScheduleId, startDate = WireDateTime.format(start),
                endDate = WireDateTime.format(end)), !availability.fitsOneWindow(start, end))
        }
    }
}
