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
import com.swpp.licenseplanner.data.ReloadOutcome
import com.swpp.licenseplanner.data.createApiHttpClient
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.ui.screens.movedScheduleId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.TimeUnit

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

    /**
     * Records every input and reload identity; each call waits for [gate] or [reloadGate]
     * when set (the delayed fake).
     */
    private class FakeAdjustment(vararg replies: AdjustmentOutcome) : AdjustmentRepository {
        val queue = ArrayDeque(replies.toList())
        val inputs = mutableListOf<AdjustmentInput>()
        var gate: CompletableDeferred<Unit>? = null
        val reloads = ArrayDeque<ReloadOutcome>()
        val reloadIdentities = mutableListOf<String?>()
        var reloadGate: CompletableDeferred<Unit>? = null
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
            inputs += input
            gate?.await()
            return queue.removeFirst()
        }
        override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome {
            reloadIdentities += scheduleCsvFile
            reloadGate?.await()
            return reloads.removeFirst()
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
        assertTrue(vm.selectPlan(1)) // and a ready plan's earlier choice is not restored
        assertEquals(AdjustmentSelection(1), vm.adjustment.value)
        assertFalse(vm.selectPlan(99))
    }

    @Test fun readyPlansRestoreNoOutcomeOrRetryOnReturn() = runTest(dispatcher) {
        for (isDemo in listOf(false, true)) {
            val adjustment = FakeAdjustment(AdjustmentOutcome.Success(adjustedA), AdjustmentOutcome.Failure(ApiError.ServerFailure(502)))
            val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, isDemo), PlanOutcome.Success(planB, isDemo)), adjustment)
            generateAB(vm)
            vm.select(1, "study-1")
            assertTrue(vm.submitAdjustment())
            advanceUntilIdle()
            assertEquals(AdjustmentState.Success("study-1"), vm.adjustment.value.state)
            assertTrue(vm.selectPlan(2))
            assertTrue(vm.selectPlan(1))
            assertEquals(AdjustmentSelection(1), vm.adjustment.value)

            vm.selectFailedSession("study-2")
            assertTrue(vm.submitAdjustment())
            advanceUntilIdle()
            assertEquals(AdjustmentSelection(1, "study-2", AdjustmentState.Failure(ApiError.ServerFailure(502))), vm.adjustment.value)
            assertTrue(vm.selectPlan(2))
            assertTrue(vm.selectPlan(1))
            assertEquals(AdjustmentSelection(1), vm.adjustment.value)
            vm.retryAdjustment() // the earlier request is not kept across plans
            assertFalse(vm.submitAdjustment())
            advanceUntilIdle()
            assertEquals(2, adjustment.inputs.size)
            assertEquals(listOf(null, null), vm.plans.value.map { it.recoveryScheduleId })
            assertTrue(adjustment.reloadIdentities.isEmpty())
        }
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
        val failures = mapOf(
            ApiError.InvalidResponse("unexpected action cancelled_external") to PlanRecovery.ReloadRequired,
            ApiError.InvalidInput("unknown schedule_csv_file") to PlanRecovery.Ready,
            ApiError.Configuration to PlanRecovery.Ready,
            ApiError.ServerFailure(500) to PlanRecovery.Ready,
            ApiError.ServerUnavailable("busy") to PlanRecovery.Ready,
            ApiError.Connection to PlanRecovery.ReloadRequired,
            ApiError.PlanConflict to PlanRecovery.ReloadRequired,
            ApiError.ServerFailure(409) to PlanRecovery.ReloadRequired,
            ApiError.PlanNotFound to PlanRecovery.Missing,
        )
        for ((error, recovery) in failures) {
            val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false)), FakeAdjustment(AdjustmentOutcome.Failure(error)))
            vm.generate()
            advanceUntilIdle()
            val before = vm.plan(1)
            vm.select(1, "study-1")
            vm.submitAdjustment()
            advanceUntilIdle()
            assertEquals(AdjustmentSelection(1, "study-1", AdjustmentState.Failure(error)), vm.adjustment.value)
            val retained = "study-1".takeIf { recovery == PlanRecovery.ReloadRequired }
            assertEquals(before.copy(recovery = recovery, recoveryScheduleId = retained), vm.plan(1))
        }
    }

    /** A real live repository against a mock server: a non-reschedule action never touches the plan. */
    @Test fun liveInvalidActionResponseKeepsPlan() = runTest(dispatcher) {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody(
                """{"schedules":${ApiJson.encodeToString(ListSerializer(ScheduleRow.serializer()), rowsA)},
                   "schedule_csv":"","action":"cancelled_external","target_schedule_id":"study-1"}""",
            ))
            val live = LiveAdjustmentRepository(server.url("/api/v1/").toString(), io = dispatcher)
            val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false)), live)
            vm.generate()
            advanceUntilIdle()
            val before = vm.plan(1)
            vm.select(1, "study-1")
            vm.switchToWednesday()
            vm.submitAdjustment()
            advanceUntilIdle()

            assertTrue((vm.adjustment.value.state as AdjustmentState.Failure).error is ApiError.InvalidResponse)
            assertEquals("study-1", vm.adjustment.value.failedScheduleId)
            assertEquals(before.copy(recovery = PlanRecovery.ReloadRequired, recoveryScheduleId = "study-1"), vm.plan(1))
            val sent = ApiJson.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals(ApiJson.parseToJsonElement("""{"monday":[{"start":"18:00","end":"20:00"}]}"""), sent["weekly_availability"])
        } finally {
            server.shutdown()
        }
    }

    @Test fun retryResendsTheIdenticalSnapshotAfterFormEdits() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(ApiError.ServerUnavailable("busy")), AdjustmentOutcome.Success(adjustedA))
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
        val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(ApiError.ServerFailure(502)), AdjustmentOutcome.Success(adjustedA))
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

    @Test fun movedMarkerFollowsOnlyTheCurrentSuccessOfItsPlan() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Success(adjustedA))
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment)
        generateAB(vm)
        vm.select(1, "study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        val success = vm.adjustment.value
        assertNull(success.failedScheduleId)
        assertEquals("study-1", movedScheduleId(vm.plan(1), success.state))

        // B shares the row ID but never shows A's marker; returning to A starts without it.
        assertTrue(vm.selectPlan(2))
        assertNull(movedScheduleId(vm.plan(2), vm.adjustment.value.state))
        assertTrue(vm.selectPlan(1))
        assertNull(movedScheduleId(vm.plan(1), vm.adjustment.value.state))

        // A new selection after a success clears the marker without sending anything.
        val again = FakeAdjustment(AdjustmentOutcome.Success(adjustedA))
        val vm2 = viewModel(FakePlanning(PlanOutcome.Success(planA, false)), again)
        vm2.generate()
        advanceUntilIdle()
        vm2.select(1, "study-1")
        vm2.submitAdjustment()
        advanceUntilIdle()
        vm2.selectFailedSession("study-2")
        assertNull(movedScheduleId(vm2.plan(1), vm2.adjustment.value.state))
        assertEquals(1, again.inputs.size)
        assertEquals(movedA, vm2.plan(1).current.schedules)
    }

    // --- Recovery after an uncertain, conflicting or missing-plan failure ---

    /** Generates A (Monday) and B (Wednesday), then fails A's study-1 adjustment with [error]. */
    private fun TestScope.failA(error: ApiError, adjustment: FakeAdjustment): PlanFormViewModel {
        adjustment.queue.addFirst(AdjustmentOutcome.Failure(error))
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment)
        generateAB(vm)
        vm.select(1, "study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        return vm
    }

    /** Neither retry nor a normal submission (even with a new row) sends anything for plan 1. */
    private fun TestScope.assertAdjustmentBlocked(vm: PlanFormViewModel, adjustment: FakeAdjustment) {
        val sent = adjustment.inputs.size
        val selection = vm.adjustment.value
        vm.retryAdjustment()
        vm.selectFailedSession("study-2")
        assertFalse(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(sent, adjustment.inputs.size)
        assertEquals(selection, vm.adjustment.value)
    }

    @Test fun uncertainFailuresRequireReloadInsteadOfRetry() = runTest(dispatcher) {
        for (error in listOf(ApiError.PlanConflict, ApiError.ServerFailure(409), ApiError.Connection, ApiError.InvalidResponse("x"))) {
            val adjustment = FakeAdjustment()
            val vm = failA(error, adjustment)
            assertEquals(PlanRecovery.ReloadRequired, vm.plan(1).recovery)
            assertEquals(rowsA, vm.plan(1).current.schedules)
            assertEquals("study-1", vm.adjustment.value.failedScheduleId)
            assertEquals("study-1", vm.plan(1).recoveryScheduleId)
            assertAdjustmentBlocked(vm, adjustment)

            assertTrue(vm.selectPlan(2)) // B shares the study IDs but inherits nothing
            assertEquals(AdjustmentSelection(2), vm.adjustment.value)
            assertTrue(vm.selectPlan(1)) // A's own target, without the earlier error
            assertEquals(AdjustmentSelection(1, "study-1"), vm.adjustment.value)
            assertAdjustmentBlocked(vm, adjustment)
            assertEquals(1, adjustment.inputs.size)
            assertTrue(adjustment.reloadIdentities.isEmpty()) // never automatic
        }
    }

    @Test fun failedThenSuccessfulReloadRequiresAFreshSelection() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Success(adjustedA))
        val vm = failA(ApiError.PlanConflict, adjustment)
        val planBBefore = vm.plan(2)
        vm.switchToWednesday()

        adjustment.reloads += ReloadOutcome.Failure(ApiError.ServerFailure(503))
        assertTrue(vm.reloadPlan())
        advanceUntilIdle()
        assertEquals(AdjustmentSelection(1, "study-1", AdjustmentState.ReloadFailure(ApiError.ServerFailure(503))), vm.adjustment.value)
        assertEquals(PlanRecovery.ReloadRequired, vm.plan(1).recovery)
        assertEquals(rowsA, vm.plan(1).current.schedules)
        assertEquals("study-1", vm.plan(1).recoveryScheduleId)
        assertAdjustmentBlocked(vm, adjustment)
        assertTrue(vm.selectPlan(2))
        assertTrue(vm.selectPlan(1)) // the target stays; the reload error is not restored
        assertEquals(AdjustmentSelection(1, "study-1"), vm.adjustment.value)
        assertAdjustmentBlocked(vm, adjustment)
        assertEquals(1, adjustment.reloadIdentities.size)

        adjustment.reloads += ReloadOutcome.Loaded(movedA)
        assertTrue(vm.reloadPlan())
        advanceUntilIdle()
        assertEquals(listOf(fileA, fileA), adjustment.reloadIdentities)
        assertEquals(AdjustmentSelection(1, null, AdjustmentState.Reloaded), vm.adjustment.value)
        val reloaded = vm.plan(1)
        assertEquals(PlanRecovery.Ready, reloaded.recovery)
        assertNull(reloaded.recoveryScheduleId)
        assertEquals(movedA, reloaded.current.schedules)
        assertEquals("", reloaded.current.scheduleCsv) // no stale CSV or summary
        assertNull(reloaded.current.agentSummary)
        assertEquals(fileA, reloaded.scheduleCsvFile)
        assertEquals(fileA, reloaded.current.scheduleCsvFile)
        assertEquals(mondayWindows, reloaded.original.weeklyAvailability)
        assertEquals(planBBefore, vm.plan(2))
        assertEquals(2, vm.plans.value.size)
        assertFalse(vm.reloadPlan()) // nothing left to reload
        assertTrue(vm.selectPlan(2))
        assertTrue(vm.selectPlan(1)) // the old target, still in the rows, is not restored
        assertEquals(AdjustmentSelection(1), vm.adjustment.value)

        // The old pending request is gone: nothing is sent until a new row is chosen and submitted.
        vm.retryAdjustment()
        assertFalse(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(1, adjustment.inputs.size)
        vm.selectFailedSession("study-1") // the same row is still a fresh choice
        assertEquals(AdjustmentSelection(1, "study-1"), vm.adjustment.value)
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        val fresh = adjustment.inputs[1]
        assertEquals(movedA, fresh.schedules)
        assertEquals(mondayWindows, fresh.original.weeklyAvailability)
        assertEquals(fileA, fresh.scheduleCsvFile)
    }

    @Test fun reloadIsSingleFlightAndLocksOtherActions() = runTest(dispatcher) {
        val adjustment = FakeAdjustment().apply { reloadGate = CompletableDeferred() }
        val vm = failA(ApiError.Connection, adjustment)
        adjustment.reloads += ReloadOutcome.Loaded(movedA)

        assertTrue(vm.reloadPlan())
        assertFalse(vm.reloadPlan())
        advanceUntilIdle()
        assertEquals(AdjustmentState.Reloading, vm.adjustment.value.state)
        assertFalse(vm.reloadPlan())
        assertFalse(vm.selectPlan(2))
        assertFalse(vm.generate())
        vm.selectFailedSession("study-2")
        assertFalse(vm.submitAdjustment())
        vm.retryAdjustment()
        assertEquals(AdjustmentSelection(1, "study-1", AdjustmentState.Reloading), vm.adjustment.value)

        adjustment.reloadGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, adjustment.reloadIdentities.size)
        assertEquals(1, adjustment.inputs.size)
        assertEquals(AdjustmentState.Reloaded, vm.adjustment.value.state)
        assertTrue(vm.selectPlan(2))
    }

    @Test fun navigationAwayAndBackKeepsTheReloadRequirement() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Success(adjustedA))
        val vm = failA(ApiError.Connection, adjustment)

        assertTrue(vm.selectPlan(1)) // reopening A directly keeps everything
        assertEquals(AdjustmentSelection(1, "study-1", AdjustmentState.Failure(ApiError.Connection)), vm.adjustment.value)

        assertTrue(vm.selectPlan(2)) // B with the same study IDs still adjusts normally
        assertEquals(AdjustmentSelection(2), vm.adjustment.value)
        assertEquals(PlanRecovery.Ready, vm.plan(2).recovery)
        assertNull(vm.plan(2).recoveryScheduleId)
        vm.selectFailedSession("study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(fileB, adjustment.inputs[1].scheduleCsvFile)
        assertEquals(wednesdayWindows, adjustment.inputs[1].original.weeklyAvailability)

        assertTrue(vm.selectPlan(1)) // reopened A shows its own target and keeps its gate, not B's outcome
        assertEquals(AdjustmentSelection(1, "study-1"), vm.adjustment.value)
        assertEquals(PlanRecovery.ReloadRequired, vm.plan(1).recovery)
        assertEquals(rowsA, vm.plan(1).current.schedules)
        assertAdjustmentBlocked(vm, adjustment)
        assertTrue(vm.selectPlan(2)) // B's success is not restored either
        assertEquals(AdjustmentSelection(2), vm.adjustment.value)
        assertEquals(2, adjustment.inputs.size)
        assertTrue(adjustment.reloadIdentities.isEmpty())
    }

    /** Entries sharing a file and study IDs each keep only their own failed session. */
    @Test fun retainedTargetsBelongToTheirOwnEntry() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(ApiError.Connection), AdjustmentOutcome.Failure(ApiError.PlanConflict))
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planA, false)), adjustment)
        repeat(2) { vm.generate(); advanceUntilIdle() }
        vm.select(1, "study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        vm.select(2, "study-2")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(listOf("study-1", "study-2"), vm.plans.value.map { it.recoveryScheduleId })

        assertTrue(vm.selectPlan(1))
        assertEquals(AdjustmentSelection(1, "study-1"), vm.adjustment.value)
        assertAdjustmentBlocked(vm, adjustment)
        assertTrue(vm.selectPlan(2))
        assertEquals(AdjustmentSelection(2, "study-2"), vm.adjustment.value)
        assertAdjustmentBlocked(vm, adjustment)

        adjustment.reloads += ReloadOutcome.Loaded(movedA)
        assertTrue(vm.reloadPlan())
        advanceUntilIdle()
        assertEquals(listOf(PlanRecovery.ReloadRequired, PlanRecovery.Ready), vm.plans.value.map { it.recovery })
        assertEquals(listOf("study-1", null), vm.plans.value.map { it.recoveryScheduleId })
        assertEquals(rowsA, vm.plan(1).current.schedules)
        assertTrue(vm.selectPlan(1))
        assertEquals(AdjustmentSelection(1, "study-1"), vm.adjustment.value)
        assertTrue(vm.selectPlan(2))
        assertEquals(AdjustmentSelection(2), vm.adjustment.value)
        assertEquals(2, adjustment.inputs.size)
        assertEquals(listOf(fileA), adjustment.reloadIdentities)
    }

    @Test fun planNotFoundMarksOnlyThatPlanMissingForTheSession() = runTest(dispatcher) {
        val adjustment = FakeAdjustment()
        val vm = failA(ApiError.PlanNotFound, adjustment)
        assertEquals(listOf(PlanRecovery.Missing, PlanRecovery.Ready), vm.plans.value.map { it.recovery })
        assertEquals(rowsA, vm.plan(1).current.schedules)
        assertFalse(vm.reloadPlan())
        assertAdjustmentBlocked(vm, adjustment)

        assertTrue(vm.selectPlan(2))
        assertTrue(vm.selectPlan(1))
        assertEquals(AdjustmentSelection(1), vm.adjustment.value)
        assertEquals(PlanRecovery.Missing, vm.plan(1).recovery)
        assertNull(vm.plan(1).recoveryScheduleId)
        assertAdjustmentBlocked(vm, adjustment)
        assertEquals(1, adjustment.inputs.size)
        assertTrue(adjustment.reloadIdentities.isEmpty())
    }

    @Test fun reloadWithoutTheExactPlanMarksItMissing() = runTest(dispatcher) {
        val adjustment = FakeAdjustment()
        val vm = failA(ApiError.PlanConflict, adjustment)
        val planBBefore = vm.plan(2)
        adjustment.reloads += ReloadOutcome.Missing
        assertTrue(vm.reloadPlan())
        advanceUntilIdle()

        assertEquals(PlanRecovery.Missing, vm.plan(1).recovery)
        assertNull(vm.plan(1).recoveryScheduleId)
        assertEquals(rowsA, vm.plan(1).current.schedules) // the local snapshot stays
        assertEquals(AdjustmentSelection(1, "study-1"), vm.adjustment.value)
        assertEquals(planBBefore, vm.plan(2)) // never substituted
        assertFalse(vm.reloadPlan())
        assertAdjustmentBlocked(vm, adjustment)
        assertTrue(vm.selectPlan(2))
        assertTrue(vm.selectPlan(1))
        assertEquals(AdjustmentSelection(1), vm.adjustment.value)
        assertAdjustmentBlocked(vm, adjustment)
        assertEquals(1, adjustment.reloadIdentities.size)
    }

    @Test fun reloadedPlansWithoutTheOldTargetOrStudyRows() = runTest(dispatcher) {
        val withoutTarget = rowsA.filter { it.scheduleId != "study-1" }
        val externalOnly = rowsA.filter { it.scheduleId.startsWith("external-") }
        for (rows in listOf(withoutTarget, externalOnly, emptyList())) {
            val adjustment = FakeAdjustment()
            val vm = failA(ApiError.Connection, adjustment)
            adjustment.reloads += ReloadOutcome.Loaded(rows)
            assertTrue(vm.reloadPlan())
            advanceUntilIdle()
            assertEquals(rows, vm.plan(1).current.schedules)
            assertEquals(AdjustmentSelection(1, null, AdjustmentState.Reloaded), vm.adjustment.value)
            assertNull(vm.plan(1).recoveryScheduleId)
            vm.selectFailedSession("study-1") // removed from the saved plan
            assertNull(vm.adjustment.value.failedScheduleId)
            assertFalse(vm.submitAdjustment())
            assertTrue(vm.selectPlan(2))
            assertTrue(vm.selectPlan(1))
            assertEquals(AdjustmentSelection(1), vm.adjustment.value)
        }
    }

    /** Two entries from separate generations may share a file; only the reloaded entry changes. */
    @Test fun reloadChangesOnlyTheCapturedEntryWhenIdentitiesMatch() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(ApiError.Connection))
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planA, false)), adjustment)
        repeat(2) { vm.generate(); advanceUntilIdle() }
        vm.select(2, "study-1")
        vm.submitAdjustment()
        advanceUntilIdle()
        val first = vm.plan(1)
        adjustment.reloads += ReloadOutcome.Loaded(movedA)
        vm.reloadPlan()
        advanceUntilIdle()

        assertEquals(first, vm.plan(1))
        assertEquals(movedA, vm.plan(2).current.schedules)
        assertEquals(listOf(fileA), adjustment.reloadIdentities)
    }

    @Test fun demoFailuresNeverRequireReload() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(ApiError.Connection), AdjustmentOutcome.Success(adjustedA))
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, true)), adjustment)
        vm.generate()
        advanceUntilIdle()
        vm.select(1, "study-1")
        vm.submitAdjustment()
        advanceUntilIdle()
        assertEquals(PlanRecovery.Ready, vm.plan(1).recovery)
        assertNull(vm.plan(1).recoveryScheduleId)
        assertFalse(vm.reloadPlan())
        vm.retryAdjustment()
        advanceUntilIdle()
        assertEquals(2, adjustment.inputs.size)
        assertTrue(adjustment.reloadIdentities.isEmpty())
    }

    // --- Recovery through the real live repository ---

    private fun rowsJson(rows: List<ScheduleRow>) =
        ApiJson.encodeToString(ListSerializer(ScheduleRow.serializer()), rows)

    private fun listJson(vararg plans: Pair<String, List<ScheduleRow>>) = plans.joinToString(",", """{"plans":[""", "]}") { (file, rows) ->
        """{"schedule_csv_file":"$file","schedule_csv_url":"/api/v1/study-plans/x.csv","schedules":${rowsJson(rows)}}"""
    }

    private fun adjustedJson(rows: List<ScheduleRow>, target: String) =
        """{"schedules":${rowsJson(rows)},"schedule_csv":"x","action":"rescheduled_study","target_schedule_id":"$target","schedule_csv_file":"$fileA"}"""

    private fun MockWebServer.requests(): List<RecordedRequest> = List(requestCount) { takeRequest() }

    /**
     * Fails A's POST with [failure], tries both mutation paths, fails one GET, reloads A from a list
     * that also holds B and an unrelated plan, then adjusts from the refreshed rows.
     */
    private fun TestScope.liveRecovery(failure: MockResponse, client: OkHttpClient? = null) {
        val server = MockWebServer().apply { start() }
        try {
            val base = server.url("/api/v1/").toString()
            val live = if (client == null) LiveAdjustmentRepository(base, io = dispatcher) else LiveAdjustmentRepository(base, client, dispatcher)
            val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), live)
            generateAB(vm)
            val planBBefore = vm.plan(2)
            val savedA = movedA
            val unrelated = "license_planner/output/study_plan_c.csv"
            server.enqueue(failure)
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"UPSTREAM_ERROR","message":"x"}}"""))
            server.enqueue(MockResponse().setBody(listJson(fileB to rowsB, unrelated to rowsB, fileA to savedA)))
            val final = savedA.map { if (it.scheduleId == "study-2") it.copy(startDate = "2026/10/12/19/00", endDate = "2026/10/12/20/00") else it }
            server.enqueue(MockResponse().setBody(adjustedJson(final, "study-2")))

            vm.select(1, "study-1")
            vm.submitAdjustment()
            advanceUntilIdle()
            assertEquals(PlanRecovery.ReloadRequired, vm.plan(1).recovery)
            vm.retryAdjustment()
            vm.selectFailedSession("study-2")
            assertFalse(vm.submitAdjustment())
            assertTrue(vm.selectPlan(2))
            assertTrue(vm.selectPlan(1))
            assertEquals(AdjustmentSelection(1, "study-1"), vm.adjustment.value)
            advanceUntilIdle()
            assertEquals(1, server.requestCount)

            assertTrue(vm.reloadPlan())
            advanceUntilIdle()
            assertTrue(vm.adjustment.value.state is AdjustmentState.ReloadFailure)
            assertEquals(rowsA, vm.plan(1).current.schedules)
            assertTrue(vm.selectPlan(2))
            assertTrue(vm.selectPlan(1))
            assertEquals(AdjustmentSelection(1, "study-1"), vm.adjustment.value)
            vm.retryAdjustment()
            assertFalse(vm.submitAdjustment())
            advanceUntilIdle()
            assertEquals(2, server.requestCount)
            assertTrue(vm.reloadPlan())
            advanceUntilIdle()
            assertEquals(savedA, vm.plan(1).current.schedules)
            assertEquals(planBBefore, vm.plan(2))
            assertEquals(2, vm.plans.value.size)
            assertFalse(vm.submitAdjustment())
            assertEquals(3, server.requestCount)

            vm.switchToWednesday()
            vm.selectFailedSession("study-2")
            assertTrue(vm.submitAdjustment())
            advanceUntilIdle()
            assertEquals(final, vm.plan(1).current.schedules)

            val requests = server.requests()
            assertEquals(listOf("POST", "GET", "GET", "POST"), requests.map { it.method })
            val reloadA = "/api/v1/study-plans?schedule_csv_file=license_planner%2Foutput%2Fstudy_plan_a.csv"
            assertEquals(listOf("/api/v1/schedules/adjust", reloadA, reloadA, "/api/v1/schedules/adjust"), requests.map { it.path })
            val sent = ApiJson.parseToJsonElement(requests[3].body.readUtf8()).jsonObject
            assertEquals(setOf("schedule_csv_file", "schedule_id", "schedules", "weekly_availability"), sent.keys)
            assertEquals(ApiJson.parseToJsonElement("\"$fileA\""), sent["schedule_csv_file"])
            assertEquals(ApiJson.parseToJsonElement("\"study-2\""), sent["schedule_id"])
            assertEquals(ApiJson.parseToJsonElement(rowsJson(savedA)), sent["schedules"])
            assertEquals(ApiJson.parseToJsonElement("""{"monday":[{"start":"18:00","end":"20:00"}]}"""), sent["weekly_availability"])
        } finally {
            server.shutdown()
        }
    }

    @Test fun liveConflictRecovery() = runTest(dispatcher) {
        liveRecovery(MockResponse().setResponseCode(409).setBody("""{"error":{"code":"PLAN_CONFLICT","message":"x"}}"""))
    }

    @Test fun liveUnknownConflictRecovery() = runTest(dispatcher) {
        liveRecovery(MockResponse().setResponseCode(409).setBody("not json"))
    }

    @Test fun liveInvalidResponseRecovery() = runTest(dispatcher) {
        liveRecovery(MockResponse().setBody(adjustedJson(rowsA, "study-2")))
    }

    @Test fun liveTimeoutRecovery() = runTest(dispatcher) {
        val shortClient = createApiHttpClient().newBuilder()
            .readTimeout(200, TimeUnit.MILLISECONDS).callTimeout(300, TimeUnit.MILLISECONDS).build()
        liveRecovery(MockResponse().setBody(adjustedJson(movedA, "study-1")).setHeadersDelay(2, TimeUnit.SECONDS), shortClient)
    }

    @Test fun livePlanNotFoundSendsNothingMore() = runTest(dispatcher) {
        val server = MockWebServer().apply { start() }
        try {
            val live = LiveAdjustmentRepository(server.url("/api/v1/").toString(), io = dispatcher)
            val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false)), live)
            vm.generate()
            advanceUntilIdle()
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"PLAN_NOT_FOUND","message":"$fileA"}}"""))
            vm.select(1, "study-1")
            vm.submitAdjustment()
            advanceUntilIdle()
            assertEquals(AdjustmentState.Failure(ApiError.PlanNotFound), vm.adjustment.value.state)
            assertEquals(PlanRecovery.Missing, vm.plan(1).recovery)
            vm.retryAdjustment()
            assertFalse(vm.reloadPlan())
            assertFalse(vm.submitAdjustment())
            advanceUntilIdle()
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
