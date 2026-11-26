package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.AvailabilityWire
import com.swpp.licenseplanner.data.model.StudyPlanRequest

/** One line describing the inputs a saved plan was created from. */
object PlanInputSummary {
    private val weekdayKeys = Weekday.entries.map { it.key }.toSet()

    /**
     * Base study days and weekly time, then applied calendar events and replaced weeks when there
     * are any, joined with 「 · 」. A base week without windows reads 「기본 시간 없음」. Pass a
     * plan's creation request, so adjustments never change the description of its inputs.
     */
    fun text(request: StudyPlanRequest): String {
        val baseDays = request.weeklyAvailability.filter { (key, windows) -> key in weekdayKeys && windows.isNotEmpty() }
        val minutes = baseDays.values.flatten().sumOf { AvailabilityWire.windowOrNull(it)?.durationMinutes ?: 0 }.toLong()
        val parts = buildList {
            if (baseDays.isEmpty()) add("기본 시간 없음")
            else add("주 ${baseDays.size}일 · 주 ${PlanGrouping.formatDuration(minutes)}")
            if (request.busySchedules.isNotEmpty()) add("캘린더 일정 ${request.busySchedules.size}개 반영")
            if (request.availabilityOverrides.isNotEmpty()) add("주별 변경 ${request.availabilityOverrides.size}주")
        }
        return parts.joinToString(" · ")
    }
}
