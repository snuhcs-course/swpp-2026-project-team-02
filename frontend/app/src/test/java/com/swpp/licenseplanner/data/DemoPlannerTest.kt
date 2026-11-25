package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.LocalTopics
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.domain.defaultAvailability
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

class DemoPlannerTest {
    private val questions = TestFixtures.spreadsheetFiveQuestions()
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

    private fun busy(id: Int, start: String, end: String, topic: String = CALENDAR_BUSY_TOPIC) =
        ScheduleRow(id.toString(), start, end, topic)

    private fun study(result: DemoResult) =
        (result as DemoResult.Plan).response.schedules.filter { it.scheduleId.startsWith("study-") }

    private fun times(rows: List<ScheduleRow>) = rows.map { it.startDate to it.endDate }

    private fun minutesOf(row: ScheduleRow): Long =
        Duration.between(WireDateTime.parseOrNull(row.startDate)!!, WireDateTime.parseOrNull(row.endDate)!!).toMinutes()

    private fun totalMinutes(rows: List<ScheduleRow>) = rows.sumOf { minutesOf(it) }

    private fun minutesByTopic(rows: List<ScheduleRow>): Map<String, Long> =
        rows.groupBy { it.topic }.mapValues { (_, topicRows) -> totalMinutes(topicRows) }

    /** Topics in the order they first appear in time. */
    private fun topicOrder(rows: List<ScheduleRow>) = rows.map { it.topic }.distinct()

    private fun overlaps(a: ScheduleRow, b: ScheduleRow): Boolean {
        val aStart = WireDateTime.parseOrNull(a.startDate)!!
        val aEnd = WireDateTime.parseOrNull(a.endDate)!!
        val bStart = WireDateTime.parseOrNull(b.startDate)!!
        val bEnd = WireDateTime.parseOrNull(b.endDate)!!
        return aStart < bEnd && bStart < aEnd
    }

    private val mondayOnly = defaultAvailability() + (Weekday.MONDAY to DayAvailability(true, LocalTime.of(18, 0), 120))

    /** All correct on Mondays 18:00–20:00; the default exam leaves the Mondays 2026-10-05 and 2026-10-12. */
    private fun mondayPlan(vararg rows: ScheduleRow, exam: LocalDate = LocalDate.of(2026, 10, 13), answers: Map<Int, String> = allCorrect) =
        DemoPlanner.plan(request(answers, CurrentLevel.ADVANCED, mondayOnly, exam).copy(busySchedules = rows.toList()))

    @Test
    fun sameInputGivesIdenticalOutput() {
        assertEquals(DemoPlanner.plan(request()), DemoPlanner.plan(request()))
        assertEquals(DemoPlanner.plan(request(allUnknown, CurrentLevel.BEGINNER)), DemoPlanner.plan(request(allUnknown, CurrentLevel.BEGINNER)))
    }

    @Test
    fun twoWeeklyWindowsOverTwoWeeksAreFullyPlannedAsDemo() {
        val twoEvenings = defaultAvailability() + mapOf(
            Weekday.MONDAY to DayAvailability(true, LocalTime.of(18, 0), 60),
            Weekday.THURSDAY to DayAvailability(true, LocalTime.of(18, 0), 60),
        )
        val req = request(allUnknown, CurrentLevel.BEGINNER, twoEvenings, LocalDate.of(2026, 10, 19))
        val rows = study(DemoPlanner.plan(req))

        assertEquals(240L, totalMinutes(rows))
        val windowDates = listOf("2026/10/05", "2026/10/08", "2026/10/12", "2026/10/15")
        assertTrue(rows.all { row ->
            val date = row.startDate.take(10)
            date in windowDates && row.startDate >= "$date/18/00" && row.endDate <= "$date/19/00"
        })
        assertEquals(windowDates.map { "$it/18/00" to "$it/19/00" }.toSet(),
            windowDates.map { date -> rows.filter { it.startDate.startsWith(date) } }
                .map { it.first().startDate to it.last().endDate }.toSet())
        val outcome = runBlocking { DemoPlanningRepository().generate(req) }
        assertTrue(outcome is PlanOutcome.Success && outcome.isDemo)
    }

    @Test
    fun busyRowAtWindowStartLeavesTheRestOfTheWindowPlanned() {
        val result = mondayPlan(busy(7, "2026/10/05/18/00", "2026/10/05/18/30"), exam = LocalDate.of(2026, 10, 6))
        val rows = study(result)

        assertEquals(90L, totalMinutes(rows))
        val external = ScheduleRow("external-7", "2026/10/05/18/00", "2026/10/05/18/30", CALENDAR_BUSY_TOPIC)
        assertTrue(rows.none { overlaps(it, external) })
        assertEquals(external, (result as DemoResult.Plan).response.schedules.first())
        // Equal scores split the 90 minutes evenly, in catalog order.
        assertEquals(
            listOf("2026/10/05/18/30" to "2026/10/05/19/00", "2026/10/05/19/00" to "2026/10/05/19/30",
                "2026/10/05/19/30" to "2026/10/05/20/00"),
            times(rows),
        )
        assertEquals(listOf(LocalTopics.SPREADSHEET_BASICS, LocalTopics.FUNCTIONS_AND_DATA, LocalTopics.CHARTS_AND_ANALYSIS),
            rows.map { it.topic })
    }

    @Test
    fun weakestTopicIsPlacedFirstAndGetsNoFewerMinutes() {
        val chartsMissed = allCorrect + (4 to UNKNOWN_KEY)
        val rows = study(DemoPlanner.plan(request(chartsMissed)))

        assertEquals(LocalTopics.CHARTS_AND_ANALYSIS, rows.first().topic)
        val minutes = minutesByTopic(rows)
        assertTrue(minutes.toString(), minutes.values.all { it <= minutes.getValue(LocalTopics.CHARTS_AND_ANALYSIS) })
        // Each topic's minutes are consecutive; equal scores follow catalog order.
        assertEquals(listOf(LocalTopics.CHARTS_AND_ANALYSIS, LocalTopics.SPREADSHEET_BASICS, LocalTopics.FUNCTIONS_AND_DATA),
            topicOrder(rows))
        assertEquals(topicOrder(rows), rows.map { it.topic }.zipWithNext().filter { (a, b) -> a != b }
            .map { it.second }.let { listOf(rows.first().topic) + it })
    }

    @Test
    fun budgetSplitsMinutesByScoreWeight() {
        // 스프레드시트 기본 2/2, 함수와 데이터 관리 1/2, 차트와 분석 0/1.
        val answers = mapOf(1 to allCorrect.getValue(1), 5 to allCorrect.getValue(5), 2 to allCorrect.getValue(2), 3 to UNKNOWN_KEY, 4 to UNKNOWN_KEY)
        val rows = study(mondayPlan(exam = LocalDate.of(2026, 10, 6), answers = answers))

        // 120 minutes: one reserved minute each, 117 by weights 2 : 1.5 : 1.
        assertEquals(
            mapOf(LocalTopics.CHARTS_AND_ANALYSIS to 53L, LocalTopics.FUNCTIONS_AND_DATA to 40L, LocalTopics.SPREADSHEET_BASICS to 27L),
            minutesByTopic(rows),
        )
        assertEquals(
            listOf("2026/10/05/18/00" to "2026/10/05/18/53", "2026/10/05/18/53" to "2026/10/05/19/33",
                "2026/10/05/19/33" to "2026/10/05/20/00"),
            times(rows),
        )
        assertEquals(listOf(LocalTopics.CHARTS_AND_ANALYSIS, LocalTopics.FUNCTIONS_AND_DATA, LocalTopics.SPREADSHEET_BASICS),
            rows.map { it.topic })
    }

    @Test
    fun selfAssessmentLevelDoesNotChangeThePlan() {
        for (answers in listOf(allCorrect, allUnknown, allCorrect + (4 to UNKNOWN_KEY))) {
            val plans = CurrentLevel.entries.map { DemoPlanner.plan(request(answers, it)) }
            assertTrue(plans.all { it == plans.first() })
        }
    }

    @Test
    fun shortfallWhenFreeMinutesAreFewerThanTopics() {
        // Two free minutes for three topics.
        val tight = busy(1, "2026/10/05/18/00", "2026/10/05/19/58")
        assertEquals(DemoResult.Shortfall(required = 3, available = 2), mondayPlan(tight, exam = LocalDate.of(2026, 10, 6)))
        val req = request(allCorrect, CurrentLevel.ADVANCED, mondayOnly, LocalDate.of(2026, 10, 6)).copy(busySchedules = listOf(tight))
        assertEquals(PlanOutcome.DemoShortfall(3, 2), runBlocking { DemoPlanningRepository().generate(req) })

        // A window fully covered by an imported event leaves nothing.
        assertEquals(DemoResult.Shortfall(required = 3, available = 0),
            mondayPlan(busy(1, "2026/10/05/00/00", "2026/10/06/00/00"), exam = LocalDate.of(2026, 10, 6)))

        // One minute per topic is enough.
        val rows = study(mondayPlan(busy(1, "2026/10/05/18/00", "2026/10/05/19/57"), exam = LocalDate.of(2026, 10, 6)))
        assertEquals(listOf(1L, 1L, 1L), rows.map { minutesOf(it) })
    }

    @Test
    fun rowsUseStudyIdsCsvHeaderOrderAndNoSummary() {
        val response = (DemoPlanner.plan(request(allUnknown, CurrentLevel.INTERMEDIATE)) as DemoResult.Plan).response
        assertEquals((1..response.schedules.size).map { "study-$it" }, response.schedules.map { it.scheduleId })
        assertEquals(response.schedules.sortedBy { it.startDate }, response.schedules)
        val lines = response.scheduleCsv.trimEnd('\n').split('\n')
        assertEquals("schedule_id,start_date,end_date,topic", lines.first())
        assertEquals(response.schedules.size + 1, lines.size)
        val first = response.schedules.first()
        assertEquals("${first.scheduleId},${first.startDate},${first.endDate},${first.topic}", lines[1])
        assertEquals("", response.agentSummary)
        // All 0%: catalog order.
        assertEquals(listOf(LocalTopics.SPREADSHEET_BASICS, LocalTopics.FUNCTIONS_AND_DATA, LocalTopics.CHARTS_AND_ANALYSIS),
            topicOrder(response.schedules))
    }

    @Test
    fun fullBankPlanCoversBothSubjectsWeakestFirst() {
        val bank = TestFixtures.publicQuestions()
        val answers = bank.associate { it.id to if (it.id in 6..14) UNKNOWN_KEY else it.correctAnswer }
        val req = (RequestMapper.map(TestFixtures.completeState(bank, answers, CurrentLevel.ADVANCED, exam = longExam))
            as MappingResult.Ready).request
        val rows = study(DemoPlanner.plan(req))
        // The three 0% 컴퓨터 일반 topics come first, then the others in catalog order.
        assertEquals(LocalTopics.catalogOrder.dropLast(1), topicOrder(rows))
    }

    @Test
    fun fullBankPlanAvoidsImportedEventsAndFillsTheRest() {
        val bank = TestFixtures.publicQuestions()
        val weekdayEvenings = defaultAvailability() +
            Weekday.entries.take(5).associateWith { DayAvailability(true, LocalTime.of(18, 0), 60) }
        val busyRows = listOf("2026/11/10", "2026/11/18", "2026/12/03").mapIndexed { index, day ->
            busy(index + 1, "$day/17/30", "$day/18/30")
        }
        val req = (RequestMapper.map(TestFixtures.completeState(bank, bank.associate { it.id to UNKNOWN_KEY }, CurrentLevel.BEGINNER,
            weekdayEvenings, start = LocalDate.of(2026, 11, 9), exam = LocalDate.of(2027, 1, 4))) as MappingResult.Ready).request
            .copy(busySchedules = busyRows)

        val first = DemoPlanner.plan(req) as DemoResult.Plan
        assertEquals(first, DemoPlanner.plan(req))
        val rows = study(first)
        // 40 weekday hours, 30 minutes of three of them taken.
        assertEquals(40L * 60 - 3 * 30, totalMinutes(rows))
        assertEquals(LocalTopics.catalogOrder.dropLast(1).toSet(), rows.map { it.topic }.toSet())
        val external = first.response.schedules.filter { it.scheduleId.startsWith("external-") }
        assertEquals(3, external.size)
        assertTrue(rows.none { row -> external.any { overlaps(row, it) } })
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

    @Test
    fun busyEventAtWindowStartIsKeptAndStudyFollowsIt() {
        val result = mondayPlan(busy(7, "2026/10/05/18/00", "2026/10/05/19/00")) as DemoResult.Plan
        assertEquals(
            listOf("2026/10/05/19/00" to "2026/10/05/20/00", "2026/10/12/18/00" to "2026/10/12/19/00",
                "2026/10/12/19/00" to "2026/10/12/20/00"),
            times(study(result)),
        )
        val external = ScheduleRow("external-7", "2026/10/05/18/00", "2026/10/05/19/00", CALENDAR_BUSY_TOPIC)
        assertEquals(listOf(external, study(result)[0]), result.response.schedules.take(2))
        assertEquals(listOf("study-1", "study-2", "study-3"), study(result).map { it.scheduleId })
    }

    @Test
    fun overlappingAndNestedEventsAreAvoidedAsOneUnion() {
        val result = mondayPlan(
            busy(1, "2026/10/05/18/00", "2026/10/05/18/40"),
            busy(2, "2026/10/05/18/10", "2026/10/05/18/30"),
            busy(3, "2026/10/05/18/30", "2026/10/05/19/00"),
        )
        assertEquals("2026/10/05/19/00" to "2026/10/05/20/00", times(study(result)).first())
        assertEquals(180L, totalMinutes(study(result)))
        assertEquals(6, (result as DemoResult.Plan).response.schedules.size)
    }

    @Test
    fun eventsTouchingTheWindowAreNotConflicts() {
        val result = mondayPlan(
            busy(1, "2026/10/05/17/00", "2026/10/05/18/00"),
            busy(2, "2026/10/05/19/00", "2026/10/05/20/00"),
        )
        assertEquals("2026/10/05/18/00" to "2026/10/05/19/00", times(study(result)).first())
        assertEquals(180L, totalMinutes(study(result)))
    }

    @Test
    fun allDayEventRemovesThatDaysWindow() {
        val rows = study(mondayPlan(busy(9, "2026/10/05/00/00", "2026/10/06/00/00")))
        assertTrue(rows.all { it.startDate.startsWith("2026/10/12/") })
        assertEquals(120L, totalMinutes(rows))
    }

    @Test
    fun fragmentedWindowsAreFilledAndSplitOnlyAtGapsOrBudgetEnds() {
        // Each 18:00–20:00 window leaves 18:00–18:30 and 19:00–19:30 free: 120 minutes, 40 per topic.
        val fragments = listOf("2026/10/05", "2026/10/12").flatMapIndexed { index, day ->
            listOf(busy(index * 2 + 1, "$day/18/30", "$day/19/00"), busy(index * 2 + 2, "$day/19/30", "$day/20/00"))
        }
        val rows = study(mondayPlan(*fragments.toTypedArray()))
        assertEquals(
            listOf(
                "2026/10/05/18/00" to "2026/10/05/18/30", "2026/10/05/19/00" to "2026/10/05/19/10",
                "2026/10/05/19/10" to "2026/10/05/19/30", "2026/10/12/18/00" to "2026/10/12/18/20",
                "2026/10/12/18/20" to "2026/10/12/18/30", "2026/10/12/19/00" to "2026/10/12/19/30",
            ),
            times(rows),
        )
        assertEquals(
            listOf(LocalTopics.SPREADSHEET_BASICS, LocalTopics.SPREADSHEET_BASICS, LocalTopics.FUNCTIONS_AND_DATA,
                LocalTopics.FUNCTIONS_AND_DATA, LocalTopics.CHARTS_AND_ANALYSIS, LocalTopics.CHARTS_AND_ANALYSIS),
            rows.map { it.topic },
        )
    }

    @Test
    fun externalRowsAppearInJsonAndCsvInTheSameOrder() {
        val response = (mondayPlan(
            busy(12, "2026/10/06/09/00", "2026/10/06/10/00", "회의, \"중요\""),
            busy(5, "2026/10/05/18/00", "2026/10/05/19/00"),
        ) as DemoResult.Plan).response
        assertEquals(
            listOf("external-5", "study-1", "external-12", "study-2", "study-3"),
            response.schedules.map { it.scheduleId },
        )
        val lines = response.scheduleCsv.trimEnd('\n').split('\n')
        assertEquals(DemoPlanner.CSV_HEADER, lines.first())
        assertEquals("external-5,2026/10/05/18/00,2026/10/05/19/00,$CALENDAR_BUSY_TOPIC", lines[1])
        assertEquals("external-12,2026/10/06/09/00,2026/10/06/10/00,\"회의, \"\"중요\"\"\"", lines[3])
        response.schedules.zip(lines.drop(1)).forEach { (row, line) ->
            assertTrue(line, line.startsWith("${row.scheduleId},${row.startDate},${row.endDate},"))
        }
        assertEquals(response.schedules.size + 1, lines.size)
    }

    @Test
    fun noImportedEventsKeepsTheExistingPlan() {
        val plain = request(allUnknown, CurrentLevel.INTERMEDIATE)
        val result = DemoPlanner.plan(plain) as DemoResult.Plan
        assertEquals(DemoPlanner.plan(plain.copy(busySchedules = emptyList())), result)
        assertFalse(result.response.schedules.any { it.scheduleId.startsWith("external-") })
        assertEquals("2026/10/05/18/00", result.response.schedules.first().startDate)
        assertEquals(result.response.schedules.size, study(result).size)
    }

    private val sundayLate = defaultAvailability() + (Weekday.SUNDAY to DayAvailability(true, LocalTime.of(23, 30), 60))

    @Test
    fun windowOverlappingMidnightIsFullyUsed() {
        // 2026-10-11 is the only Sunday before 2026-10-18; its window runs to 00:30 the next day.
        val rows = study(DemoPlanner.plan(request(allCorrect, CurrentLevel.ADVANCED, sundayLate, LocalDate.of(2026, 10, 18))))
        assertEquals(60L, totalMinutes(rows))
        assertEquals("2026/10/11/23/30", rows.first().startDate)
        assertEquals("2026/10/12/00/30", rows.last().endDate)

        // An exam at Monday 00:00 clips the spilling window to the 30 minutes before it.
        val clipped = study(DemoPlanner.plan(request(allCorrect, CurrentLevel.ADVANCED, sundayLate, LocalDate.of(2026, 10, 19))))
        assertEquals(90L, totalMinutes(clipped))
        assertEquals("2026/10/19/00/00", clipped.last().endDate)
    }

    @Test
    fun overrideWeekReplacesThatWeeksWindows() {
        val base = request(allCorrect, CurrentLevel.ADVANCED, sundayLate, LocalDate.of(2026, 10, 19))

        // Emptying the week of Monday 2026-10-05 skips Sunday 2026-10-11.
        val emptyFirstWeek = study(DemoPlanner.plan(base.copy(availabilityOverrides = mapOf("2026/10/05" to emptyMap()))))
        assertEquals(30L, totalMinutes(emptyFirstWeek))
        assertEquals("2026/10/18/23/30", emptyFirstWeek.first().startDate)

        // A replacement Tuesday window is used instead of that week's Sunday.
        val replaced = study(DemoPlanner.plan(base.copy(availabilityOverrides = mapOf(
            "2026/10/05" to mapOf("tuesday" to listOf(TimeWindow(start = "09:00", durationMinutes = 45))),
        ))))
        assertEquals(75L, totalMinutes(replaced))
        assertEquals("2026/10/06/09/00", replaced.first().startDate)
        assertTrue(replaced.none { it.startDate.startsWith("2026/10/11") })
        assertEquals("2026/10/19/00/00", replaced.last().endDate)
    }
}
