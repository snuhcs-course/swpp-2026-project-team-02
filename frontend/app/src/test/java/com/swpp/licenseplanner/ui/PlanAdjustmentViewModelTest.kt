package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentOutcome
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.ApiJson
import com.swpp.licenseplanner.data.DemoAdjustmentRepository
import com.swpp.licenseplanner.data.LiveAdjustmentRepository
import com.swpp.licenseplanner.data.PlanOutcome
import com.swpp.licenseplanner.data.PlanningRepository
import com.swpp.licenseplanner.data.QuestionOutcome
import com.swpp.licenseplanner.data.QuestionRepository
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.Weekday
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** Session plans and adjustment lifecycle, with fake or local repositories. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanAdjustmentViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeQuestions(private val questions: QuestionOutcome) : QuestionRepository {
        override suspend fun load(certificationId: String) = questions
    }

    private class FakePlanning(vararg replies: PlanOutcome) : PlanningRepository {
        val queue = ArrayDeque(replies.toList())
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun generate(request: StudyPlanRequest): PlanOutcome {
            gate?.await()
            return queue.removeFirst()
        }
    }

    /** Records every input; each call waits for [gate] when set (the delayed fake). */
    private class FakeAdjustment(vararg replies: AdjustmentOutcome) : AdjustmentRepository {
        val queue = ArrayDeque(replies.toList())
        val inputs = mutableListOf<AdjustmentInput>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
            inputs += input
            gate?.await()
            return queue.removeFirst()
        }
    }

    private val mondayWindows = mapOf("monday" to listOf(TimeWindow("18:00", "20:00")))
    private val wednesdayWindows = mapOf("wednesday" to listOf(TimeWindow("18:00", "19:00")))

    private val fileA = "license_planner/output/study_plan_a.csv"
    private val fileB = "license_planner/output/study_plan_b.csv"
    private val rowsA = listOf(
        ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수"),
        ScheduleRow("study-2", "2026/10/05/19/00", "2026/10/05/20/00", "차트"),
        ScheduleRow("external-1", "2026/10/06/18/00", "2026/10/06/19/00", ""),
    )
    private val rowsB = listOf(
        ScheduleRow("study-1", "2026/10/07/18/00", "2026/10/07/19/00", "셀 참조"),
        ScheduleRow("study-2", "2026/10/14/18/00", "2026/10/14/19/00", "셀 참조"),
    )
    private val planA = StudyPlanResponse(rowsA, "csv-a", "요약 A", fileA)
    private val planB = StudyPlanResponse(rowsB, "csv-b", "요약 B", fileB)

    /** study-1 of A moved to the next Monday. */
    private val movedA = rowsA.map { if (it.scheduleId == "study-1") it.copy(startDate = "2026/10/12/18/00", endDate = "2026/10/12/19/00") else it }
    private val adjustedA = StudyPlanResponse(movedA, "csv-a2", null, null)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** A loaded, complete form with Monday 18:00–20:00 (plan A's windows). */
    private fun TestScope.viewModel(planning: PlanningRepository, adjustment: AdjustmentRepository) =
        PlanFormViewModel(FakeQuestions(QuestionOutcome.Loaded(TestFixtures.publicQuestions())), planning, adjustment, LocalDate.of(2026, 10, 5)).apply {
            selectCertification(CERTIFICATION_ID)
            setExamDate(LocalDate.of(2026, 11, 16))
            setLevel(CurrentLevel.INTERMEDIATE)
            loadQuestions()
            advanceUntilIdle()
            form.value.questions.forEach { answer(it.id, it.correctAnswer) }
            setDayEnabled(Weekday.MONDAY, true)
            stepDuration(Weekday.MONDAY, 2)
        }

    /** Switches the form to plan B's Wednesday 18:00–19:00 window. */
    private fun PlanFormViewModel.switchToWednesday() {
        setDayEnabled(Weekday.MONDAY, false)
        setDayEnabled(Weekday.WEDNESDAY, true)
    }

    private fun TestScope.generateAB(vm: PlanFormViewModel) {
        assertTrue(vm.generate())
        advanceUntilIdle()
        vm.switchToWednesday()
        assertTrue(vm.generate())
        advanceUntilIdle()
    }

    private fun PlanFormViewModel.plan(ordinal: Int) = plans.value.single { it.ordinal == ordinal }

    private fun PlanFormViewModel.select(ordinal: Int, scheduleId: String) {
        assertTrue(selectPlan(ordinal))
        selectFailedSession(scheduleId)
    }

    // --- Canonical session collection ---

    @Test fun twoPlansSharingStudy1StaySeparateWithOriginalWindows() = runTest(dispatcher) {
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), FakeAdjustment())
        generateAB(vm)
        vm.setDayEnabled(Weekday.FRIDAY, true) // a later edit

        assertEquals(listOf(1, 2), vm.plans.value.map { it.ordinal })
        assertEquals(rowsA, vm.plan(1).current.schedules)
        assertEquals(rowsB, vm.plan(2).current.schedules)
        assertEquals(mondayWindows, vm.plan(1).original.weeklyAvailability)
        assertEquals(wednesdayWindows, vm.plan(2).original.weeklyAvailability)
        assertEquals(fileA, vm.plan(1).scheduleCsvFile)
        assertTrue(vm.selectPlan(2))
        assertEquals(2, vm.adjustment.value.planOrdinal)
    }

    @Test fun sameFileFromSeparateGenerationsMakesTwoEntries() = runTest(dispatcher) {
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planA, false)), FakeAdjustment())
        vm.generate()
        advanceUntilIdle()
        vm.generate()
        advanceUntilIdle()
        assertEquals(listOf(1, 2), vm.plans.value.map { it.ordinal })
        assertEquals(listOf(fileA, fileA), vm.plans.value.map { it.scheduleCsvFile })
        assertEquals(2, vm.generatedPlan.value)
    }

    @Test fun revisitingTheResultRegistersNothing() = runTest(dispatcher) {
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false)), FakeAdjustment())
        vm.generate()
        advanceUntilIdle()
        // Result, list and adjustment screens only read state.
        repeat(3) { vm.selectPlan(1); vm.retryGeneration() }
        advanceUntilIdle()
        assertEquals(1, vm.plans.value.size)
        assertEquals(1, vm.generatedPlan.value)
    }

    @Test fun formEditsFailuresAndShortfallKeepPlans() = runTest(dispatcher) {
        val vm = viewModel(
            FakePlanning(
                PlanOutcome.Success(planA, false),
                PlanOutcome.Failure(ApiError.ServerFailure(502)),
                PlanOutcome.DemoShortfall(required = 12, available = 3),
            ),
            FakeAdjustment(),
        )
        vm.generate()
        advanceUntilIdle()
        val stored = vm.plans.value

        vm.switchToWednesday()
        assertEquals(GenerationState.Idle, vm.generation.value)
        assertNull(vm.generatedPlan.value)
        vm.generate()
        advanceUntilIdle()
        assertEquals(GenerationState.Failure(ApiError.ServerFailure(502)), vm.generation.value)
        vm.stepDuration(Weekday.WEDNESDAY, 1)
        vm.generate()
        advanceUntilIdle()
        assertEquals(GenerationState.DemoShortfall(12, 3), vm.generation.value)

        assertEquals(stored, vm.plans.value)
        assertEquals(mondayWindows, vm.plan(1).original.weeklyAvailability)
    }

    @Test fun missingLiveIdentityIsViewableButNotAdjustable() = runTest(dispatcher) {
        val adjustment = FakeAdjustment()
        val vm = viewModel(
            FakePlanning(
                PlanOutcome.Success(planA.copy(scheduleCsvFile = null), false),
                PlanOutcome.Success(planA.copy(scheduleCsvFile = "  "), false),
                PlanOutcome.Success(planA.copy(scheduleCsvFile = null), true),
            ),
            adjustment,
        )
        repeat(3) { vm.generate(); advanceUntilIdle() }

        assertEquals(listOf(false, false, true), vm.plans.value.map { it.hasIdentity })
        assertEquals(listOf(null, null, null), vm.plans.value.map { it.scheduleCsvFile })
        for (ordinal in 1..2) {
            vm.select(ordinal, "study-1")
            assertEquals(rowsA, vm.plan(ordinal).current.schedules)
            assertFalse(vm.submitAdjustment())
        }
        advanceUntilIdle()
        assertTrue(adjustment.inputs.isEmpty())
    }

    @Test fun emptyPlanIsKeptAndCannotBeAdjusted() = runTest(dispatcher) {
        val adjustment = FakeAdjustment()
        val vm = viewModel(FakePlanning(PlanOutcome.Success(StudyPlanResponse(emptyList(), scheduleCsvFile = fileA), false)), adjustment)
        vm.generate()
        advanceUntilIdle()
        assertEquals(0, vm.plan(1).studyCount)
        vm.select(1, "study-1")
        assertNull(vm.adjustment.value.failedScheduleId)
        assertFalse(vm.submitAdjustment())
        assertTrue(adjustment.inputs.isEmpty())
    }

    @Test fun selectionRulesForStudyExternalAndPlans() = runTest(dispatcher) {
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), FakeAdjustment())
        generateAB(vm)

        vm.select(1, "study-1")
        vm.selectFailedSession("external-1") // external rows are not selectable
        vm.selectFailedSession("study-9") // not in this plan
        assertEquals("study-1", vm.adjustment.value.failedScheduleId)
        vm.selectFailedSession("study-2")
        assertEquals(AdjustmentSelection(1, "study-2"), vm.adjustment.value)

        assertTrue(vm.selectPlan(1)) // same plan keeps the selection
        assertEquals("study-2", vm.adjustment.value.failedScheduleId)
        assertTrue(vm.selectPlan(2)) // another plan starts clean
        assertEquals(AdjustmentSelection(2), vm.adjustment.value)
        assertFalse(vm.selectPlan(99))
    }

    // --- Adjustment lifecycle ---

    @Test fun delayedAdjustmentLocksEverythingThenReplacesOnlyItsPlan() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Success(adjustedA)).apply { gate = CompletableDeferred() }
        val planning = FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false), PlanOutcome.Success(planB, false))
        val vm = viewModel(planning, adjustment)
        generateAB(vm)
        val planBBefore = vm.plan(2)

        vm.select(1, "study-1")
        assertTrue(vm.submitAdjustment())
        assertFalse(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(AdjustmentState.Loading, vm.adjustment.value.state)
        assertFalse(vm.selectPlan(2))
        vm.selectFailedSession("study-2")
        assertFalse(vm.generate())
        vm.retryAdjustment()
        assertEquals(AdjustmentSelection(1, "study-1", AdjustmentState.Loading), vm.adjustment.value)

        adjustment.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, adjustment.inputs.size)
        assertEquals(AdjustmentSelection(1, null, AdjustmentState.Success("study-1")), vm.adjustment.value)
        val updated = vm.plan(1)
        assertEquals(movedA, updated.current.schedules)
        assertEquals("csv-a2", updated.current.scheduleCsv)
        assertNull(updated.current.agentSummary) // no stale generation summary
        assertEquals(fileA, updated.scheduleCsvFile)
        assertEquals(mondayWindows, updated.original.weeklyAvailability)
        assertEquals(planBBefore, vm.plan(2))
        assertEquals(2, vm.plans.value.size)
        assertTrue(vm.generate()) // unlocked
    }

    @Test fun generationInFlightBlocksAdjustment() = runTest(dispatcher) {
        val planning = FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false))
        val adjustment = FakeAdjustment()
        val vm = viewModel(planning, adjustment)
        vm.generate()
        advanceUntilIdle()
        vm.select(1, "study-1")
        planning.gate = CompletableDeferred()
        vm.stepDuration(Weekday.MONDAY, 1)
        assertTrue(vm.generate())
        advanceUntilIdle()
        assertFalse(vm.submitAdjustment())
        assertTrue(adjustment.inputs.isEmpty())
    }

    @Test fun failuresKeepPlanSelectionAndIdentity() = runTest(dispatcher) {
        val failures = listOf(
            ApiError.InvalidResponse("unexpected action cancelled_external"),
            ApiError.InvalidInput("unknown schedule_csv_file"), // a stale/deleted file
            ApiError.Configuration,
            ApiError.ServerFailure(500),
            ApiError.Connection,
        )
        for (error in failures) {
            val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false)), FakeAdjustment(AdjustmentOutcome.Failure(error)))
            vm.generate()
            advanceUntilIdle()
            val before = vm.plans.value
            vm.select(1, "study-1")
            vm.submitAdjustment()
            advanceUntilIdle()
            assertEquals(AdjustmentSelection(1, "study-1", AdjustmentState.Failure(error)), vm.adjustment.value)
            assertEquals(before, vm.plans.value)
            assertEquals(fileA, vm.plan(1).scheduleCsvFile)
        }
    }

    /** A real live repository against a mock server: a non-reschedule action never touches the plan. */
    @Test fun liveInvalidActionResponseKeepsPlan() = runTest(dispatcher) {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(
                """{"schedules":${ApiJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(ScheduleRow.serializer()), rowsA)},
                   "schedule_csv":"","action":"cancelled_external","target_schedule_id":"study-1"}""",
            ))
            val live = LiveAdjustmentRepository(server.url("/api/v1/").toString(), io = dispatcher)
            val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false)), live)
            vm.generate()
            advanceUntilIdle()
            val before = vm.plans.value
            vm.select(1, "study-1")
            vm.switchToWednesday()
            vm.submitAdjustment()
            advanceUntilIdle()

            assertTrue((vm.adjustment.value.state as AdjustmentState.Failure).error is ApiError.InvalidResponse)
            assertEquals("study-1", vm.adjustment.value.failedScheduleId)
            assertEquals(before, vm.plans.value)
            val sent = ApiJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(ApiJson.parseToJsonElement("""{"monday":[{"start":"18:00","end":"20:00"}]}"""), sent["weekly_availability"])
        } finally {
            server.shutdown()
        }
    }

    @Test fun retryResendsTheIdenticalSnapshotAfterFormEdits() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(ApiError.Connection), AdjustmentOutcome.Success(adjustedA))
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false)), adjustment)
        vm.generate()
        advanceUntilIdle()
        vm.select(1, "study-1")
        vm.submitAdjustment()
        advanceUntilIdle()

        vm.switchToWednesday()
        vm.retryAdjustment()
        advanceUntilIdle()

        assertEquals(2, adjustment.inputs.size)
        assertEquals(adjustment.inputs[0], adjustment.inputs[1])
        assertEquals(mondayWindows, adjustment.inputs[1].original.weeklyAvailability)
        assertEquals(fileA, adjustment.inputs[1].scheduleCsvFile)
        assertEquals(1, vm.plans.value.size)
        assertEquals(AdjustmentState.Success("study-1"), vm.adjustment.value.state)
    }

    @Test fun changingSelectionAfterFailureBuildsANewRequest() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(ApiError.Connection), AdjustmentOutcome.Success(adjustedA))
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false)), adjustment)
        vm.generate()
        advanceUntilIdle()
        vm.select(1, "study-1")
        vm.submitAdjustment()
        advanceUntilIdle()

        vm.selectFailedSession("study-2")
        assertEquals(AdjustmentState.Idle, vm.adjustment.value.state)
        vm.retryAdjustment() // nothing to retry once the selection changed
        advanceUntilIdle()
        assertEquals(1, adjustment.inputs.size)
    }

    @Test fun secondAdjustmentUsesLatestRowsAndOriginalWindows() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Success(adjustedA), AdjustmentOutcome.Success(adjustedA))
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment)
        generateAB(vm)
        vm.select(1, "study-1")
        vm.submitAdjustment()
        advanceUntilIdle()

        vm.selectFailedSession("study-2")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()

        val second = adjustment.inputs[1]
        assertEquals(movedA, second.schedules)
        assertEquals("study-2", second.scheduleId)
        assertEquals(fileA, second.scheduleCsvFile)
        assertEquals(mondayWindows, second.original.weeklyAvailability)
    }

    @Test fun selectedPlanUsesItsOriginalContextInBothFlavors() = runTest(dispatcher) {
        for (isDemo in listOf(false, true)) {
            val adjustment = FakeAdjustment(AdjustmentOutcome.Success(adjustedA))
            val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, isDemo), PlanOutcome.Success(planB, isDemo)), adjustment)
            generateAB(vm) // A with Monday, then B with Wednesday
            vm.select(1, "study-1")
            assertTrue(vm.submitAdjustment())
            advanceUntilIdle()

            val input = adjustment.inputs.single()
            assertEquals(mondayWindows, input.original.weeklyAvailability)
            assertEquals("2026/11/16/00/00", input.original.examDate)
            assertEquals(rowsA, input.schedules)
            assertEquals(if (isDemo) null else fileA, input.scheduleCsvFile)
        }
    }

    /** The real demo adjuster moves A's session within A's Monday window, not B's Wednesday. */
    @Test fun demoAdjustmentUsesThePlansOriginalWindows() = runTest(dispatcher) {
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, true), PlanOutcome.Success(planB, true)), DemoAdjustmentRepository())
        generateAB(vm)
        vm.select(1, "study-1")
        vm.submitAdjustment()
        advanceUntilIdle()
        assertEquals(movedA, vm.plan(1).current.schedules)
        assertNull(vm.plan(1).current.agentSummary)
    }

    @Test fun demoNoSlotKeepsPlanAndSelection() = runTest(dispatcher) {
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, true)), FakeAdjustment(AdjustmentOutcome.DemoNoSlot))
        vm.generate()
        advanceUntilIdle()
        val before = vm.plans.value
        vm.select(1, "study-1")
        vm.submitAdjustment()
        advanceUntilIdle()
        assertEquals(AdjustmentSelection(1, "study-1", AdjustmentState.DemoNoSlot), vm.adjustment.value)
        assertEquals(before, vm.plans.value)
    }

    @Test fun refreshedPlanRequiresNewSelectionAndPreservesOriginalWindows() = runTest(dispatcher) {
        val saved = adjustedA.copy(scheduleCsvFile = fileA)
        val adjustment = FakeAdjustment(AdjustmentOutcome.Refreshed(saved))
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false)), adjustment)
        vm.generate()
        advanceUntilIdle()
        vm.select(1, "study-1")
        vm.submitAdjustment()
        advanceUntilIdle()
        assertEquals(AdjustmentSelection(1, null, AdjustmentState.Refreshed), vm.adjustment.value)
        assertEquals(saved, vm.plan(1).current)
        assertEquals(mondayWindows, vm.plan(1).original.weeklyAvailability)
        assertFalse(vm.submitAdjustment())
        vm.retryAdjustment()
        advanceUntilIdle()
        assertEquals(1, adjustment.inputs.size)
    }
}
