package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.RemainingSession
import com.swpp.licenseplanner.data.model.ScheduleRow
import java.time.LocalDateTime

data class DestinationValidation(val rows: List<ScheduleRow> = emptyList(), val code: String? = null)

object RemainingDestinations {
    fun validateDestinations(rows: List<ScheduleRow>, instructions: List<RemainingSession>,
                             destinations: Map<String, LocalDateTime>, availability: StudyAvailability,
                             preparationStart: LocalDateTime, examDate: LocalDateTime,
                             replanFrom: LocalDateTime, now: LocalDateTime,
                             confirmOutside: Boolean = false): DestinationValidation {
        fun rejected(code: String) = DestinationValidation(code = code)
        val sessions = PlanGrouping.sessions(rows).associateBy { it.scheduleId }
        val sources = instructions.map { it.scheduleId }.toSet()
        val replacements = instructions.map { it.replacementScheduleId }.toSet()
        val id = Regex("study-([0-9]+)")
        fun suffix(value: String) = id.matchEntire(value)?.groupValues?.get(1)?.toLongOrNull()
        val highest = rows.mapNotNull { suffix(it.scheduleId) }.maxOrNull() ?: 0L
        if (instructions.isEmpty() || sources.size != instructions.size || replacements.size != instructions.size ||
            destinations.keys != sources || sessions.size != rows.size || instructions.any { instruction ->
                val source = sessions[instruction.scheduleId]
                source == null || source.isExternal || instruction.remainingMinutes <= 0 ||
                    instruction.remainingMinutes.toLong() > source.durationMinutes ||
                    if (instruction.replacementScheduleId == instruction.scheduleId) {
                        instruction.remainingMinutes.toLong() != source.durationMinutes
                    } else instruction.replacementScheduleId in sessions ||
                        (suffix(instruction.replacementScheduleId) ?: -1) <= highest
            }) return rejected("INVALID_INPUT")
        val originals = rows.associateBy { it.scheduleId }
        val candidate = rows.filterNot { it.scheduleId in sources }.toMutableList()
        val ordered = instructions.sortedWith(compareBy<RemainingSession> { originals.getValue(it.scheduleId).startDate }
            .thenBy { originals.getValue(it.scheduleId).endDate }.thenBy { suffix(it.scheduleId) })
        for (instruction in ordered) {
            val start = destinations.getValue(instruction.scheduleId)
            val end = start.plusMinutes(instruction.remainingMinutes.toLong())
            if (start < now.plusMinutes(1)) return rejected("MANUAL_MOVE_IN_PAST")
            if (start < preparationStart) return rejected("BEFORE_PREPARATION_START")
            if (start < replanFrom) return rejected("INVALID_INPUT")
            if (end > examDate) return rejected("EXAM_BOUND_EXCEEDED")
            val overlaps = PlanGrouping.sessions(candidate).filter { it.start < end && start < it.end }
            if (overlaps.any { !it.isExternal }) return rejected("STUDY_SESSION_OVERLAP")
            if (overlaps.isNotEmpty()) return rejected("EXTERNAL_EVENT_OVERLAP")
            if (!confirmOutside && !availability.fitsOneWindow(start, end)) return rejected("OUTSIDE_STUDY_WINDOWS")
            candidate += originals.getValue(instruction.scheduleId).copy(scheduleId = instruction.replacementScheduleId,
                startDate = WireDateTime.format(start), endDate = WireDateTime.format(end))
        }
        return DestinationValidation(candidate.sortedWith(compareBy<ScheduleRow> { it.startDate }.thenBy { it.endDate }.thenBy { it.scheduleId }))
    }
}
