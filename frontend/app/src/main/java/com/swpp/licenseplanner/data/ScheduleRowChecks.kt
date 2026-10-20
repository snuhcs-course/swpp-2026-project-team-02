package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.domain.WireDateTime

/**
 * Checks shared by every server row set that may replace a session plan: nonblank unique IDs
 * and parseable wire timestamps with end after start. Null when the rows are usable.
 */
internal fun scheduleRowProblem(rows: List<ScheduleRow>): String? {
    val ids = rows.map { it.scheduleId }
    if (ids.any { it.isBlank() }) return "blank schedule_id"
    if (ids.toSet().size != ids.size) return "duplicate schedule_id"
    val badRow = rows.firstOrNull { row ->
        val start = WireDateTime.parseOrNull(row.startDate)
        val end = WireDateTime.parseOrNull(row.endDate)
        start == null || end == null || !end.isAfter(start)
    }
    return badRow?.let { "invalid schedule row ${it.scheduleId}" }
}
