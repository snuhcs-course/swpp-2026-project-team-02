package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.AvailabilityWire
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** Per-week available and assigned study minutes for the generated plan view. */
data class WeeklyTimeUsage(
    val monday: LocalDate,
    val availableMinutes: Long,
    val plannedMinutes: Long,
    val days: List<DailyTimeUsage>,
)

data class DailyTimeUsage(
    val date: LocalDate,
    val availableMinutes: Long,
    val plannedMinutes: Long,
)

/** Calculates a Monday-based weekly comparison using the same windows and busy rows as generation. */
object WeeklyTimeUsageCalculator {
    fun calculate(request: StudyPlanRequest, rows: List<ScheduleRow>): List<WeeklyTimeUsage> {
        val preparationStart = WireDateTime.parseOrNull(request.preparationStart) ?: return emptyList()
        val start = preparationStart.toLocalDate()
        val examDateTime = WireDateTime.parseOrNull(request.examDate) ?: return emptyList()
        val exam = examDateTime.toLocalDate()
        if (!examDateTime.isAfter(preparationStart)) return emptyList()
        val context = AvailabilityWire.context(request) ?: return emptyList()

        val plannedByDate = PlanGrouping.sessions(rows)
            .filterNot { it.isExternal }
            .groupBy { it.start.toLocalDate() }
            .mapValues { (_, sessions) -> sessions.sumOf { it.durationMinutes } }

        val firstMonday = start.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        val lastMonday = exam.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
        return generateSequence(firstMonday) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(lastMonday) }
            .map { monday ->
                val sunday = monday.plusDays(6)
                val firstDay = maxOf(start, monday)
                val lastDay = minOf(exam, sunday)
                val days = (0L..6L).map { offset ->
                    val date = monday.plusDays(offset)
                    var available = 0L
                    if (!date.isBefore(firstDay) && !date.isAfter(lastDay)) {
                        val dayStart = date.atStartOfDay()
                        val dayEnd = date.plusDays(1).atStartOfDay()
                        for (window in context.windowsFor(date)) {
                            val clippedStart = maxOf(window.start, dayStart, preparationStart)
                            val clippedEnd = minOf(window.end, dayEnd, examDateTime)
                            if (clippedStart < clippedEnd) {
                                available += freeSegments(clippedStart, clippedEnd, request.busySchedules)
                                    .sumOf { Duration.between(it.first, it.second).toMinutes() }
                            }
                        }
                    }
                    DailyTimeUsage(date, available, plannedByDate[date] ?: 0L)
                }
                WeeklyTimeUsage(
                    monday = monday,
                    availableMinutes = days.sumOf { it.availableMinutes },
                    plannedMinutes = days.sumOf { it.plannedMinutes },
                    days = days,
                )
            }
            .toList()
    }

    private fun freeSegments(
        start: LocalDateTime,
        end: LocalDateTime,
        busyRows: List<ScheduleRow>,
    ): List<Pair<LocalDateTime, LocalDateTime>> {
        var segments = listOf(start to end)
        for (busy in busyRows.sortedBy { it.startDate }) {
            val busyStart = WireDateTime.parseOrNull(busy.startDate) ?: continue
            val busyEnd = WireDateTime.parseOrNull(busy.endDate) ?: continue
            segments = segments.flatMap { (segmentStart, segmentEnd) ->
                if (busyStart >= segmentEnd || busyEnd <= segmentStart) listOf(segmentStart to segmentEnd)
                else buildList {
                    if (segmentStart < busyStart) add(segmentStart to minOf(segmentEnd, busyStart))
                    if (busyEnd < segmentEnd) add(maxOf(segmentStart, busyEnd) to segmentEnd)
                }
            }
        }
        return segments.filter { (left, right) -> left < right }
    }
}
