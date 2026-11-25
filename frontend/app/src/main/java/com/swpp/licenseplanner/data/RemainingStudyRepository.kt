package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.RemainingStudy
import com.swpp.licenseplanner.domain.RemainingWork
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.WireDateTime
import java.time.LocalDateTime

/** Full selected-plan capture; reports and acknowledgments never enter the HTTP model. */
data class RemainingStudyInput(val source: SessionPlan, val work: RemainingWork, val replanFrom: LocalDateTime,
                              val destinations: Map<String, LocalDateTime>? = null, val confirmOutside: Boolean = false)

interface RemainingStudyRepository {
    suspend fun replan(input: RemainingStudyInput): AdjustmentOutcome
}

/** Validates the complete partition; overlaps between protected rows may already exist. */
internal fun remainingResponseProblem(input: RemainingStudyInput, response: StudyPlanResponse): String? {
    val source = input.source
    if (runCatching { RemainingStudy.calculate(source) }.getOrNull() != input.work) return "remaining work changed"
    scheduleRowProblem(response.schedules)?.let { return it }
    if (response.scheduleCsvFile != source.scheduleCsvFile) return "plan identity changed"
    val context = AvailabilityWire.context(source.original) ?: return "invalid original windows"
    if (response.availabilitySnapshot?.let { AvailabilityWire.context(it) != context } == true) return "windows changed"
    val original = source.current.schedules.associateBy { it.scheduleId }
    val returned = response.schedules.associateBy { it.scheduleId }
    val instructions = input.work.instructions
    if (input.destinations != null && input.destinations.keys != instructions.map { it.scheduleId }.toSet()) return "incomplete destinations"
    val sources = instructions.map { it.scheduleId }.toSet()
    val protected = original.filterKeys { it !in sources }
    if (returned.keys != protected.keys + instructions.map { it.replacementScheduleId }) return "row partition changed"
    if (protected.any { (id, row) -> returned[id] != row }) return "protected row changed"
    val preparation = WireDateTime.parseOrNull(source.original.preparationStart) ?: return "invalid preparation"
    val exam = WireDateTime.parseOrNull(source.original.examDate) ?: return "invalid exam"
    val lower = maxOf(preparation, input.replanFrom)
    val sessions = PlanGrouping.sessions(response.schedules)
    for (instruction in instructions) {
        val row = returned.getValue(instruction.replacementScheduleId)
        val session = sessions.single { it.scheduleId == row.scheduleId }
        if (row.topic != original.getValue(instruction.scheduleId).topic || session.durationMinutes != instruction.remainingMinutes.toLong()) {
            return "remaining topic or minutes changed"
        }
        if (session.start < lower || session.end > exam ||
            (!context.fitsOneWindow(session.start, session.end) && !(input.destinations != null && input.confirmOutside))) return "invalid placement window"
        if (input.destinations != null && session.start != input.destinations[instruction.scheduleId]) return "confirmed start changed"
        if (sessions.any { it.scheduleId != session.scheduleId && it.start < session.end && session.start < it.end }) return "placed row overlaps"
    }
    if (parseRemainingCsv(response.scheduleCsv)?.associateBy { it.scheduleId } != returned) return "CSV does not match rows"
    return null
}

internal fun remainingCsv(rows: List<ScheduleRow>): String = buildString {
    append("schedule_id,start_date,end_date,topic\n")
    rows.forEach { row ->
        append(listOf(row.scheduleId, row.startDate, row.endDate, row.topic).joinToString(",") {
            if (it.any { c -> c in ",\"\r\n" }) "\"${it.replace("\"", "\"\"") }\"" else it
        }).append('\n')
    }
}

/** Reads quoted CSV fields, including embedded newlines, without rewriting topic text. */
private fun parseRemainingCsv(csv: String): List<ScheduleRow>? {
    val records = mutableListOf<List<String>>()
    var record = mutableListOf<String>()
    val field = StringBuilder()
    var quoted = false
    var closed = false
    var index = 0
    while (index < csv.length) {
        val char = csv[index++]
        if (quoted) {
            if (char == '"') {
                if (index < csv.length && csv[index] == '"') { field.append('"'); index++ }
                else { quoted = false; closed = true }
            } else field.append(char)
        } else when (char) {
            '"' -> { if (field.isNotEmpty() || closed) return null; quoted = true }
            ',', '\r', '\n' -> {
                record += field.toString(); field.clear(); closed = false
                if (char != ',') {
                    records += record; record = mutableListOf()
                    if (char == '\r' && index < csv.length && csv[index] == '\n') index++
                }
            }
            else -> { if (closed) return null; field.append(char) }
        }
    }
    if (quoted) return null
    if (record.isNotEmpty() || field.isNotEmpty() || closed) { record += field.toString(); records += record }
    if (records.firstOrNull() != listOf("schedule_id", "start_date", "end_date", "topic")) return null
    val values = records.drop(1)
    if (values.any { it.size != 4 } || values.map { it[0] }.toSet().size != values.size) return null
    return values.map { ScheduleRow(it[0], it[1], it[2], it[3]) }
}
