package com.swpp.licenseplanner.domain

import com.swpp.licenseplanner.data.model.ScheduleRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PlanGroupingTest {
    private fun row(id: String, start: String, end: String, topic: String = "함수와 데이터 관리") =
        ScheduleRow(id, start, end, topic)

    @Test
    fun fridayAndMondayFallInConsecutiveWeeks() {
        val weeks = PlanGrouping.weeks(listOf(
            row("study-2", "2026/10/12/18/00", "2026/10/12/19/00"),
            row("study-1", "2026/10/09/18/00", "2026/10/09/19/00"),
        ))
        assertEquals(2, weeks.size)
        assertEquals(LocalDate.of(2026, 10, 5), weeks[0].monday)
        assertEquals(LocalDate.of(2026, 10, 11), weeks[0].sunday)
        assertEquals(listOf("study-1"), weeks[0].days[4].sessions.map { it.scheduleId }) // Friday
        assertEquals(LocalDate.of(2026, 10, 12), weeks[1].monday)
        assertEquals(listOf("study-2"), weeks[1].days[0].sessions.map { it.scheduleId })
    }

    @Test
    fun everyWeekHasSevenDaysIncludingEmptyOnesAndGapWeeks() {
        val weeks = PlanGrouping.weeks(listOf(
            row("study-1", "2026/10/05/18/00", "2026/10/05/19/00"),
            row("study-2", "2026/10/21/18/00", "2026/10/21/19/00"),
        ))
        assertEquals(3, weeks.size)
        assertTrue(weeks.all { it.days.size == 7 })
        assertTrue(weeks[0].days[1].sessions.isEmpty()) // Tuesday is an empty day
        assertTrue(weeks[1].days.all { it.sessions.isEmpty() })
    }

    @Test
    fun unsortedInputIsSorted() {
        val sessions = PlanGrouping.sessions(listOf(
            row("study-3", "2026/10/07/18/00", "2026/10/07/19/00"),
            row("study-1", "2026/10/05/18/00", "2026/10/05/19/00"),
            row("study-2", "2026/10/05/09/00", "2026/10/05/10/00"),
        ))
        assertEquals(listOf("study-2", "study-1", "study-3"), sessions.map { it.scheduleId })
    }

    @Test
    fun durationFromStartAndEnd() {
        val session = PlanGrouping.sessions(listOf(row("study-1", "2026/10/05/18/00", "2026/10/05/19/30"))).single()
        assertEquals(90L, session.durationMinutes)
        assertEquals("1시간 30분", PlanGrouping.formatDuration(session.durationMinutes))
        assertEquals("2시간", PlanGrouping.formatDuration(120))
        assertEquals("25분", PlanGrouping.formatDuration(25))
    }

    @Test
    fun twoSameDaySessionsKeepActualDurations() {
        val weeks = PlanGrouping.weeks(listOf(
            row("study-1", "2026/10/05/18/00", "2026/10/05/18/25", "스프레드시트 기본"),
            row("study-2", "2026/10/05/18/30", "2026/10/05/19/50", "차트와 분석"),
        ))
        val monday = weeks.single().days[0].sessions
        assertEquals(listOf(25L, 80L), monday.map { it.durationMinutes })
        assertEquals("1시간 20분", PlanGrouping.formatDuration(monday[1].durationMinutes))
    }

    @Test
    fun unknownTopicIsPreservedAndExternalRowsAreMarked() {
        val sessions = PlanGrouping.sessions(listOf(
            row("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "서버가 새로 만든 아주 긴 주제 이름: 피벗 테이블과 매크로"),
            row("external-12", "2026/10/07/13/00", "2026/10/07/15/00", ""),
        ))
        assertEquals("서버가 새로 만든 아주 긴 주제 이름: 피벗 테이블과 매크로", sessions[0].topic)
        assertFalse(sessions[0].isExternal)
        assertTrue(sessions[1].isExternal)
    }

    @Test
    fun emptySchedulesGiveNoWeeks() {
        assertTrue(PlanGrouping.weeks(emptyList()).isEmpty())
    }
}
