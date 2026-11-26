package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class PlanInputSummaryTest {
    private val evening = TimeWindow(start = "19:00", durationMinutes = 90)
    private val baseWeek = mapOf(
        "monday" to listOf(evening),
        "wednesday" to listOf(evening),
        "friday" to listOf(evening),
        "saturday" to listOf(TimeWindow(start = "10:00", durationMinutes = 120)),
    )
    private val busy = (1..3).map {
        ScheduleRow("external-$it", "2026/11/2$it/12/00", "2026/11/2$it/13/00", "약속")
    }
    private val replacedWeek = mapOf("2026/11/30" to mapOf("tuesday" to listOf(evening)))

    private fun request(
        weekly: Map<String, List<TimeWindow>> = baseWeek,
        busySchedules: List<ScheduleRow> = emptyList(),
        overrides: Map<String, Map<String, List<TimeWindow>>> = emptyMap(),
    ) = StudyPlanRequest(
        certificationId = "computer_specialist_level_2",
        preparationStart = "2026/11/16/00/00",
        examDate = "2026/12/07/09/00",
        selfAssessment = "beginner",
        assessmentResults = AssessmentResults("computer_specialist_level_2", emptyList()),
        studyDaysPerWeek = weekly.size,
        weeklyAvailability = weekly,
        busySchedules = busySchedules,
        availabilityOverrides = overrides,
    )

    @Test
    fun baseWeekGivesDaysAndWeeklyTime() {
        assertEquals("주 4일 · 주 6시간 30분", PlanInputSummary.text(request()))
    }

    @Test
    fun appliedCalendarEventsAreCounted() {
        assertEquals("주 4일 · 주 6시간 30분 · 캘린더 일정 3개 반영", PlanInputSummary.text(request(busySchedules = busy)))
    }

    @Test
    fun replacedWeeksFollowCalendarEvents() {
        assertEquals(
            "주 4일 · 주 6시간 30분 · 캘린더 일정 3개 반영 · 주별 변경 1주",
            PlanInputSummary.text(request(busySchedules = busy, overrides = replacedWeek)),
        )
    }

    @Test
    fun emptyBaseWeekNeverReadsZeroDays() {
        assertEquals("기본 시간 없음 · 주별 변경 1주", PlanInputSummary.text(request(weekly = emptyMap(), overrides = replacedWeek)))
    }

    @Test
    fun sameDayEndFormFromOlderPlansCountsItsMinutes() {
        val older = mapOf(
            "monday" to listOf(TimeWindow(start = "19:00", end = "20:30"), TimeWindow(start = "06:00", end = "06:30")),
            "thursday" to emptyList(),
        )
        assertEquals("주 1일 · 주 2시간", PlanInputSummary.text(request(weekly = older)))
    }
}
