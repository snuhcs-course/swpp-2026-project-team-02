package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.AvailabilityWire
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

/** Why a chosen manual time was refused; [code] is the server's error code for the same rule. */
enum class ManualMoveRejection(val code: String) {
    InPast("MANUAL_MOVE_IN_PAST"),
    BeforePreparationStart("BEFORE_PREPARATION_START"),
    ExamBoundExceeded("EXAM_BOUND_EXCEEDED"),
    StudySessionOverlap("STUDY_SESSION_OVERLAP"),
    ExternalEventOverlap("EXTERNAL_EVENT_OVERLAP"),
    OutsideStudyWindows("OUTSIDE_STUDY_WINDOWS"),
    ;

    companion object {
        fun ofCode(code: String?): ManualMoveRejection? = entries.firstOrNull { it.code == code }
    }
}

sealed interface DemoManualAdjustment {
    data class Moved(val schedules: List<ScheduleRow>, val csv: String) : DemoManualAdjustment
    data class Rejected(val reason: ManualMoveRejection) : DemoManualAdjustment
    data object InvalidTarget : DemoManualAdjustment
}

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
 * interval inside one of the plan's original effective windows (base week or week replacement,
 * including a window that continues past midnight) that starts at or after both its own
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

        val context = AvailabilityWire.context(original) ?: return DemoAdjustment.NoSlot
        var date: LocalDate = lowerBound.toLocalDate()
        while (date.atStartOfDay().isBefore(exam)) {
            // Full intervals, so a session can use a window that continues past midnight.
            for (window in context.windowsFor(date)) {
                val slot = firstFit(maxOf(window.start, lowerBound), minOf(window.end, exam), duration, others)
                if (slot != null) return moved(rows, targetId, slot)
            }
            date = date.plusDays(1)
        }
        return DemoAdjustment.NoSlot
    }

    /**
     * The local manual policy, matching the server's manual mode: the target keeps its duration and
     * moves to [newStart]. Checks run in a fixed order (past, preparation start, exam, another
     * study session, an external event, study windows); [confirmOutsideAvailability] waives only
     * the last one. [confirmExternalOverlap] waives only external overlaps. [now] is the current local minute.
     */
    fun moveManually(
        original: StudyPlanRequest,
        rows: List<ScheduleRow>,
        targetId: String,
        newStart: LocalDateTime,
        now: LocalDateTime,
        confirmOutsideAvailability: Boolean,
        confirmExternalOverlap: Boolean = false,
    ): DemoManualAdjustment {
        val sessions = PlanGrouping.sessions(rows)
        val target = sessions.firstOrNull { it.scheduleId == targetId && !it.isExternal }
            ?: return DemoManualAdjustment.InvalidTarget
        val preparation = WireDateTime.parseOrNull(original.preparationStart) ?: return DemoManualAdjustment.InvalidTarget
        val exam = WireDateTime.parseOrNull(original.examDate) ?: return DemoManualAdjustment.InvalidTarget
        val newEnd = newStart.plus(Duration.between(target.start, target.end))
        val overlapping = sessions.filter { it.scheduleId != targetId && it.start.isBefore(newEnd) && newStart.isBefore(it.end) }
        val rejection = when {
            newStart.isBefore(now) -> ManualMoveRejection.InPast
            newStart.isBefore(preparation) -> ManualMoveRejection.BeforePreparationStart
            newEnd.isAfter(exam) -> ManualMoveRejection.ExamBoundExceeded
            overlapping.any { !it.isExternal } -> ManualMoveRejection.StudySessionOverlap
            overlapping.isNotEmpty() && !confirmExternalOverlap -> ManualMoveRejection.ExternalEventOverlap
            !confirmOutsideAvailability && AvailabilityWire.context(original)?.fitsOneWindow(newStart, newEnd) != true ->
                ManualMoveRejection.OutsideStudyWindows
            else -> null
        }
        if (rejection != null) return DemoManualAdjustment.Rejected(rejection)
        val moved = moved(rows, targetId, newStart)
        return DemoManualAdjustment.Moved(moved.schedules, moved.csv)
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
