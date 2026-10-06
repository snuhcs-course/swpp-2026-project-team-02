package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.domain.defaultAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

class DemoPlannerTest {
    private val questions = TestFixtures.publicQuestions()
    private val allCorrect = questions.associate { it.id to it.correctAnswer }
    private val allUnknown = questions.associate { it.id to UNKNOWN_KEY }
    private val longExam = LocalDate.of(2027, 3, 1)

    private fun request(
        answers: Map<Int, String> = allCorrect,
        level: CurrentLevel = CurrentLevel.ADVANCED,
        availability: Map<Weekday, DayAvailability> = TestFixtures.gen01Availability(),
        exam: LocalDate = longExam,
    ): StudyPlanRequest = (RequestMapper.map(
        TestFixtures.completeState(questions, answers, level, availability, exam = exam),
    ) as MappingResult.Ready).request

    private fun sessions(request: StudyPlanRequest): Int =
        (DemoPlanner.plan(request) as DemoResult.Plan).response.schedules.size

    @Test
    fun sameInputGivesIdenticalOutput() {
        assertEquals(DemoPlanner.plan(request()), DemoPlanner.plan(request()))
        assertEquals(DemoPlanner.plan(request(allUnknown, CurrentLevel.BEGINNER)), DemoPlanner.plan(request(allUnknown, CurrentLevel.BEGINNER)))
    }

    @Test
    fun fewerCorrectAnswersNeverReduceSessions() {
        for (level in CurrentLevel.entries) {
            var previous = 0
            // Remove correct answers one question at a time.
            for (wrong in 0..questions.size) {
                val answers = questions.mapIndexed { i, q -> q.id to if (i < wrong) UNKNOWN_KEY else q.correctAnswer }.toMap()
                val count = sessions(request(answers, level))
                assertTrue("level=$level wrong=$wrong count=$count previous=$previous", count >= previous)
                previous = count
            }
        }
    }

    @Test
    fun lowerChosenLevelNeverReducesSessions() {
        for (answers in listOf(allCorrect, allUnknown)) {
            val advanced = sessions(request(answers, CurrentLevel.ADVANCED))
            val intermediate = sessions(request(answers, CurrentLevel.INTERMEDIATE))
            val beginner = sessions(request(answers, CurrentLevel.BEGINNER))
            assertTrue(intermediate >= advanced && beginner >= intermediate)
        }
        // All correct, advanced: 3 topics × 2 = 6 × 0.5 = 3; beginner: 6 × 2.0 = 12.
        assertEquals(3, sessions(request(allCorrect, CurrentLevel.ADVANCED)))
        assertEquals(12, sessions(request(allCorrect, CurrentLevel.BEGINNER)))
    }

    @Test
    fun saturdayOnlySessionsRespectWindowStartAndExam() {
        val saturday = defaultAvailability() + (Weekday.SATURDAY to DayAvailability(true, LocalTime.of(10, 0), 180))
        val req = request(allCorrect, CurrentLevel.ADVANCED, saturday)
        val rows = (DemoPlanner.plan(req) as DemoResult.Plan).response.schedules
        assertEquals(3, rows.size)
        for (row in rows) {
            val start = WireDateTime.parseOrNull(row.startDate)!!
            val end = WireDateTime.parseOrNull(row.endDate)!!
            assertEquals(DayOfWeek.SATURDAY, start.dayOfWeek)
            assertTrue(!start.toLocalTime().isBefore(LocalTime.of(10, 0)) && !end.toLocalTime().isAfter(LocalTime.of(13, 0)))
            assertTrue(!start.toLocalDate().isBefore(LocalDate.of(2026, 10, 5)) && start.toLocalDate().isBefore(longExam))
        }
        assertEquals("2026/10/10/10/00", rows.first().startDate)
        assertEquals("2026/10/10/11/00", rows.first().endDate)
    }

    @Test
    fun shortWindowUsesWholeWindow() {
        val shortWindow = defaultAvailability() + (Weekday.MONDAY to DayAvailability(true, LocalTime.of(7, 0), 30))
        val rows = (DemoPlanner.plan(request(allCorrect, CurrentLevel.ADVANCED, shortWindow)) as DemoResult.Plan).response.schedules
        assertEquals("2026/10/05/07/00", rows.first().startDate)
        assertEquals("2026/10/05/07/30", rows.first().endDate)
    }

    @Test
    fun shortfallReturnsCountsWithoutRows() {
        val saturday = defaultAvailability() + (Weekday.SATURDAY to DayAvailability(true, LocalTime.of(10, 0), 60))
        // 2026-10-05 → 2026-11-02 has four Saturdays; all-unknown beginner needs (3 × 5) × 2 = 30.
        val result = DemoPlanner.plan(request(allUnknown, CurrentLevel.BEGINNER, saturday, LocalDate.of(2026, 11, 2)))
        assertEquals(DemoResult.Shortfall(required = 30, available = 4), result)
        val outcome = runBlocking { DemoPlanningRepository().generate(request(allUnknown, CurrentLevel.BEGINNER, saturday, LocalDate.of(2026, 11, 2))) }
        assertEquals(PlanOutcome.DemoShortfall(30, 4), outcome)
    }

    @Test
    fun rowsUseStudyIdsCsvHeaderOrderAndNoSummary() {
        val response = (DemoPlanner.plan(request(allUnknown, CurrentLevel.INTERMEDIATE)) as DemoResult.Plan).response
        assertEquals((1..response.schedules.size).map { "study-$it" }, response.schedules.map { it.scheduleId })
        val lines = response.scheduleCsv.trimEnd('\n').split('\n')
        assertEquals("schedule_id,start_date,end_date,topic", lines.first())
        assertEquals(response.schedules.size + 1, lines.size)
        val first = response.schedules.first()
        assertEquals("${first.scheduleId},${first.startDate},${first.endDate},${first.topic}", lines[1])
        assertEquals("", response.agentSummary)
        // Sorted by start, weakest topic first (all 0%: catalog order).
        assertEquals("스프레드시트 기본", response.schedules[0].topic)
        assertEquals("함수와 데이터 관리", response.schedules[1].topic)
        assertEquals("차트와 분석", response.schedules[2].topic)
        assertTrue(runBlocking { DemoPlanningRepository().generate(request()) }.let { it is PlanOutcome.Success && it.isDemo })
    }

    @Test
    fun unmappedQuestionsFormTheirOwnLocalTopic() {
        val loaded = QuestionAdapter.parse(TestFixtures.threeQuestionJson(), "computer_specialist_level_2")
        val answers = mapOf(2 to "B", 42 to UNKNOWN_KEY, 4 to "A")
        val req = (RequestMapper.map(TestFixtures.completeState(loaded, answers, CurrentLevel.ADVANCED, exam = longExam))
            as MappingResult.Ready).request
        val rows = (DemoPlanner.plan(req) as DemoResult.Plan).response.schedules
        assertEquals("미분류 예시 문항", rows.first().topic)
    }
}
