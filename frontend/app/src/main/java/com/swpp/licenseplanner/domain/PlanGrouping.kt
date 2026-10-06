package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.ScheduleRow
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** One returned schedule row with parsed times; [topic] is kept exactly as returned. */
data class PlanSession(
    val scheduleId: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
    val topic: String,
) {
    val durationMinutes: Long get() = Duration.between(start, end).toMinutes()
    /** Existing schedules (`external-…`) are marked, not shown as study sessions. */
    val isExternal: Boolean get() = scheduleId.startsWith("external-")
}

data class DayPlan(val date: LocalDate, val sessions: List<PlanSession>)

data class WeekPlan(val monday: LocalDate, val days: List<DayPlan>) {
    val sunday: LocalDate get() = monday.plusDays(6)
}

/** Sorts returned rows and groups them into Monday-based weeks of seven days (VIEW-01/02/04). */
object PlanGrouping {
    /** Rows with unparseable timestamps are skipped; the live repository rejects them earlier. */
    fun sessions(rows: List<ScheduleRow>): List<PlanSession> = rows.mapNotNull { row ->
        val start = WireDateTime.parseOrNull(row.startDate) ?: return@mapNotNull null
        val end = WireDateTime.parseOrNull(row.endDate) ?: return@mapNotNull null
        PlanSession(row.scheduleId, start, end, row.topic)
    }.sortedWith(compareBy<PlanSession> { it.start }.thenBy { it.end })

    /** Every week from the first session's Monday to the last session's week; empty input gives no weeks. */
    fun weeks(rows: List<ScheduleRow>): List<WeekPlan> {
        val sorted = sessions(rows)
        if (sorted.isEmpty()) return emptyList()
        val byDate = sorted.groupBy { it.start.toLocalDate() }
        val firstMonday = sorted.first().start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val lastMonday = sorted.last().start.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return generateSequence(firstMonday) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(lastMonday) }
            .map { monday ->
                WeekPlan(monday, (0L..6L).map { offset ->
                    val date = monday.plusDays(offset)
                    DayPlan(date, byDate[date].orEmpty())
                })
            }
            .toList()
    }

    /** `N시간 M분`, dropping a zero part: 90 → 1시간 30분, 120 → 2시간, 25 → 25분. */
    fun formatDuration(minutes: Long): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours == 0L -> "${rest}분"
            rest == 0L -> "${hours}시간"
            else -> "${hours}시간 ${rest}분"
        }
    }
}
