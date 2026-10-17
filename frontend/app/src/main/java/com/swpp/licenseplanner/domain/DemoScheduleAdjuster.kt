package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

sealed interface DemoAdjustment {
    /** [schedules] keep the input order; only the target's start and end changed. */
    data class Moved(val schedules: List<ScheduleRow>, val csv: String) : DemoAdjustment
    /** No permitted interval fits before the exam. */
    data object NoSlot : DemoAdjustment
    /** The target is missing, external, or has unreadable times. */
    data object InvalidTarget : DemoAdjustment
}

/**
 * Local demonstration rule for moving one failed study session.
 * It is not the backend's adjustment policy. The session moves to the first conflict-free
 * interval inside the plan's original weekday windows that starts at or after both its own
 * end and the latest end of every other study session with exactly the same topic, and that
 * ends no later than the original exam timestamp. Intervals are half-open.
 */
object DemoScheduleAdjuster {
    private const val CSV_HEADER = "schedule_id,start_date,end_date,topic"

    fun adjust(original: StudyPlanRequest, rows: List<ScheduleRow>, targetId: String): DemoAdjustment {
        val sessions = PlanGrouping.sessions(rows)
        val target = sessions.firstOrNull { it.scheduleId == targetId && !it.isExternal }
            ?: return DemoAdjustment.InvalidTarget
        val exam = WireDateTime.parseOrNull(original.examDate) ?: return DemoAdjustment.NoSlot
        val duration = Duration.between(target.start, target.end)
        val others = sessions.filter { it.scheduleId != targetId }
        val lowerBound = others
            .filter { !it.isExternal && it.topic == target.topic }
            .maxOfOrNull { it.end }
            ?.let { maxOf(it, target.end) } ?: target.end

        val windows = Weekday.entries.associateWith { day ->
            original.weeklyAvailability[day.key].orEmpty()
                .map { LocalTime.parse(it.start) to LocalTime.parse(it.end) }
                .sortedBy { it.first }
        }
        var date: LocalDate = lowerBound.toLocalDate()
        while (date.atStartOfDay().isBefore(exam)) {
            for ((windowStart, windowEnd) in windows.getValue(Weekday.entries[date.dayOfWeek.value - 1])) {
                val slot = firstFit(maxOf(date.atTime(windowStart), lowerBound), minOf(date.atTime(windowEnd), exam), duration, others)
                if (slot != null) return moved(rows, targetId, slot)
            }
            date = date.plusDays(1)
        }
        return DemoAdjustment.NoSlot
    }

    /** Earliest start in [from, until) whose whole [duration] avoids every other row. */
    private fun firstFit(from: LocalDateTime, until: LocalDateTime, duration: Duration, others: List<PlanSession>): LocalDateTime? {
        var start = from
        while (!start.plus(duration).isAfter(until)) {
            val end = start.plus(duration)
            val blocking = others.filter { it.start.isBefore(end) && start.isBefore(it.end) }
            if (blocking.isEmpty()) return start
            start = blocking.maxOf { it.end }
        }
        return null
    }

    private fun moved(rows: List<ScheduleRow>, targetId: String, start: LocalDateTime): DemoAdjustment.Moved {
        val updated = rows.map { row ->
            if (row.scheduleId != targetId) return@map row
            val oldStart = WireDateTime.parseOrNull(row.startDate)!!
            val oldEnd = WireDateTime.parseOrNull(row.endDate)!!
            row.copy(
                startDate = WireDateTime.format(start),
                endDate = WireDateTime.format(start.plus(Duration.between(oldStart, oldEnd))),
            )
        }
        val csv = buildString {
            append(CSV_HEADER).append('\n')
            updated.forEach { row ->
                append(listOf(row.scheduleId, row.startDate, row.endDate, row.topic).joinToString(",") { csvField(it) })
                append('\n')
            }
        }
        return DemoAdjustment.Moved(updated, csv)
    }

    /** Minimal quoting, as in the demo planner's CSV. */
    private fun csvField(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + value.replace("\"", "\"\"") + "\"" else value
}
