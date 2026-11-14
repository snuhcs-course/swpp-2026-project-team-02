package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeeklyTimeUsageCalculatorTest {
    @Test
    fun calculatesEachPreparationWeekAndSubtractsBusyTime() {
        val request = StudyPlanRequest(
            certificationId = "test",
            preparationStart = "2026/10/05/18/30",
            examDate = "2026/10/12/00/00",
            selfAssessment = "test",
            assessmentResults = AssessmentResults("test", emptyList()),
            studyDaysPerWeek = 2,
            weeklyAvailability = mapOf(
                "monday" to listOf(TimeWindow("18:00", "20:00")),
                "wednesday" to listOf(TimeWindow("18:00", "19:00")),
            ),
            busySchedules = listOf(
                ScheduleRow("external-1", "2026/10/05/19/00", "2026/10/05/19/30"),
            ),
        )
        val rows = listOf(
            ScheduleRow("study-1", "2026/10/05/19/30", "2026/10/05/20/00", "매크로 활용"),
            ScheduleRow("study-2", "2026/10/07/18/00", "2026/10/07/19/00", "함수와 데이터 관리"),
            ScheduleRow("external-1", "2026/10/05/19/00", "2026/10/05/19/30"),
        )

        val weeks = WeeklyTimeUsageCalculator.calculate(request, rows)

        assertEquals(2, weeks.size)
        assertEquals(LocalDate.of(2026, 10, 5), weeks[0].monday)
        assertEquals(120L, weeks[0].availableMinutes)
        assertEquals(90L, weeks[0].plannedMinutes)
        assertEquals(7, weeks[0].days.size)
        assertEquals(60L, weeks[0].days[0].availableMinutes)
        assertEquals(30L, weeks[0].days[0].plannedMinutes)
        assertEquals(60L, weeks[0].days[2].availableMinutes)
        assertEquals(60L, weeks[0].days[2].plannedMinutes)
        assertEquals(LocalDate.of(2026, 10, 12), weeks[1].monday)
        assertEquals(0L, weeks[1].availableMinutes)
        assertEquals(0L, weeks[1].plannedMinutes)
    }

    @Test
    fun includesWeeksWithoutStudySessionsAndClipsAvailabilityAtExamDate() {
        val request = StudyPlanRequest(
            certificationId = "test",
            preparationStart = "2026/10/05/00/00",
            examDate = "2026/10/20/00/00",
            selfAssessment = "test",
            assessmentResults = AssessmentResults("test", emptyList()),
            studyDaysPerWeek = 1,
            weeklyAvailability = mapOf(
                "monday" to listOf(TimeWindow("18:00", "20:00")),
                "tuesday" to listOf(TimeWindow("18:00", "20:00")),
            ),
        )

        val weeks = WeeklyTimeUsageCalculator.calculate(request, emptyList())

        assertEquals(3, weeks.size)
        assertEquals(listOf(240L, 240L, 120L), weeks.map { it.availableMinutes })
        assertTrue(weeks.all { it.plannedMinutes == 0L })
    }
}
