package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.AvailabilityWire
import com.swpp.licenseplanner.data.model.RemainingSession
import com.swpp.licenseplanner.data.model.ScheduleRow
import java.time.Duration
import java.time.LocalDateTime

/** One current study row and the effort that will be reviewed before rearranging it. */
data class RemainingContribution(
    val source: ScheduleRow,
    val report: StudyCheckIn?,
    val plannedMinutes: Int,
    val remainingMinutes: Int,
    val replacementId: String,
)

data class RemainingWork(val contributions: List<RemainingContribution>, val totalMinutes: Long) {
    val instructions: List<RemainingSession> get() = contributions.filter { it.remainingMinutes > 0 }.map {
        RemainingSession(it.source.scheduleId, it.remainingMinutes, it.replacementId)
    }
}

/** Effort is applied once: a reported positive remainder becomes a new, unreported row. */
object RemainingStudy {
    private val studyId = Regex("study-([0-9]+)")

    fun calculate(plan: SessionPlan, onlyScheduleId: String? = null): RemainingWork {
        val rows = plan.current.schedules
        require(rows.map { it.scheduleId }.toSet().size == rows.size) { "중복된 일정이 있어요." }
        val current = rows.filterNot { it.scheduleId.startsWith("external-") }
        fun suffix(id: String): Long = requireNotNull(studyId.matchEntire(id)?.groupValues?.get(1)?.toLongOrNull()) {
            "공부 일정 번호를 처리할 수 없어요."
        }
        var next = (current.map { suffix(it.scheduleId) } +
            (plan.checkIns.keys + plan.keptConflicts.keys).mapNotNull {
                if (studyId.matches(it)) suffix(it) else null
            }).maxOrNull() ?: 0L
        val sorted = current.filter { onlyScheduleId == null || it.scheduleId == onlyScheduleId }
            .sortedWith(compareBy<ScheduleRow> { it.startDate }.thenBy { it.endDate }.thenBy { suffix(it.scheduleId) })
        var total = 0L
        val contributions = sorted.map { row ->
            val start = requireNotNull(WireDateTime.parseOrNull(row.startDate)) { "일정 시간을 읽을 수 없어요." }
            val end = requireNotNull(WireDateTime.parseOrNull(row.endDate)) { "일정 시간을 읽을 수 없어요." }
            val duration = Duration.between(start, end).toMinutes()
            require(duration in 1..Int.MAX_VALUE.toLong()) { "공부 시간을 처리할 수 없어요." }
            val planned = duration.toInt()
            val report = plan.checkInFor(row.scheduleId)
            require(report == null || report.isValid) { "공부 기록을 읽을 수 없어요." }
            val remaining = when (report?.status) {
                StudyCheckInStatus.Done -> 0
                StudyCheckInStatus.Partly -> (planned - report.actualMinutes).coerceAtLeast(0)
                StudyCheckInStatus.Missed, null -> planned
            }
            total = Math.addExact(total, remaining.toLong())
            val replacement = if (remaining > 0 && report != null) "study-${Math.incrementExact(next).also { next = it }}" else row.scheduleId
            RemainingContribution(row, report, planned, remaining, replacement)
        }
        return RemainingWork(contributions, total)
    }

    /**
     * Complete whole-session first fit; null means no complete placement under this ordering.
     * Each remainder must sit in a study session of at least [StudySessions.MIN_MINUTES], and once
     * the last one is placed no session from the lower bound on may have been cut below it.
     */
    fun place(plan: SessionPlan, work: RemainingWork, replanFrom: LocalDateTime, now: LocalDateTime): List<ScheduleRow>? {
        val context = AvailabilityWire.context(plan.original) ?: return null
        val preparation = WireDateTime.parseOrNull(plan.original.preparationStart) ?: return null
        val exam = WireDateTime.parseOrNull(plan.original.examDate) ?: return null
        val lower = maxOf(preparation, replanFrom, now.withSecond(0).withNano(0).plusMinutes(1))
        val sources = work.instructions.map { it.scheduleId }.toSet()
        val candidate = plan.current.schedules.filterNot { it.scheduleId in sources }.toMutableList()
        val original = PlanGrouping.sessions(plan.current.schedules)
        val pending = work.contributions.filter { it.remainingMinutes > 0 }
        val placedIds = mutableSetOf<String>()
        for ((index, contribution) in pending.withIndex()) {
            placedIds += contribution.replacementId
            val futureFrom = if (index == pending.lastIndex) lower else null
            var date = lower.toLocalDate()
            var placed: ScheduleRow? = null
            while (date.atStartOfDay() < exam && placed == null) {
                for (window in context.windowsFor(date)) {
                    var start = maxOf(lower, window.start)
                    val until = minOf(exam, window.end)
                    while (start.plusMinutes(contribution.remainingMinutes.toLong()) <= until) {
                        val end = start.plusMinutes(contribution.remainingMinutes.toLong())
                        val busy = PlanGrouping.sessions(candidate)
                        val blockers = busy.filter { it.start < end && start < it.end }
                        if (blockers.isNotEmpty()) {
                            start = blockers.maxOf { it.end }
                            continue
                        }
                        val row = contribution.source.copy(scheduleId = contribution.replacementId,
                            startDate = WireDateTime.format(start), endDate = WireDateTime.format(end))
                        if (StudySessions.keepsSessions(PlanGrouping.sessions(candidate + row), placedIds, original, futureFrom)) {
                            placed = row
                            break
                        }
                        start = StudySessions.nextEnd(start, busy) ?: break
                    }
                    if (placed != null) break
                }
                date = date.plusDays(1)
            }
            candidate += placed ?: return null
        }
        return candidate.sortedWith(compareBy<ScheduleRow> { it.startDate }.thenBy { it.endDate }.thenBy { it.scheduleId })
    }
}
