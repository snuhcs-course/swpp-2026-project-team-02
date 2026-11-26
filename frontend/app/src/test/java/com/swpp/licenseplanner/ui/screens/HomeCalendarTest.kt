package com.swpp.licenseplanner.ui.screens

import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.StudyCheckIn
import com.swpp.licenseplanner.domain.StudyCheckInStatus
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCalendarTest {
    private val request = StudyPlanRequest(
        CERTIFICATION_ID, "2026/11/01/00/00", "2026/12/01/00/00", "beginner",
        AssessmentResults(CERTIFICATION_ID, emptyList()), 1, emptyMap(),
    )

    @Test fun monthGridIncludesAdjacentDaysWithoutLosingLeapDay() {
        val days = calendarDays(YearMonth.of(2028, 2))
        assertEquals(LocalDate.of(2028, 1, 30), days.first())
        assertEquals(LocalDate.of(2028, 2, 29), days[30])
        assertEquals(LocalDate.of(2028, 3, 4), days.last())
        assertEquals(35, days.size)
    }

    @Test fun entriesKeepPlanOwnershipAndRecordedStatus() {
        val rows = listOf(
            ScheduleRow("study-1", "2026/11/25/18/00", "2026/11/25/19/00", "함수"),
            ScheduleRow("external-1", "2026/11/25/12/00", "2026/11/25/13/00", "약속"),
        )
        val first = SessionPlan(1, request, true, StudyPlanResponse(rows), null,
            checkIns = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Done, 60)))
        val second = SessionPlan(2, request, true, StudyPlanResponse(rows.take(1)), null)
        val entries = calendarEntries(listOf(first, second))
        assertEquals(listOf(1, 1, 2), entries.map { it.planOrdinal })
        assertEquals(listOf(null, StudyCheckInStatus.Done, null), entries.map { it.status })
    }
}
