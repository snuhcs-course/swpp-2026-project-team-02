package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentOutcome
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.ApiJson
import com.swpp.licenseplanner.data.AvailabilityProfileRepository
import com.swpp.licenseplanner.data.BytesPlanStore
import com.swpp.licenseplanner.data.DemoAdjustmentRepository
import com.swpp.licenseplanner.data.LiveAdjustmentRepository
import com.swpp.licenseplanner.data.ManualMove
import com.swpp.licenseplanner.data.MemoryPlanBytes
import com.swpp.licenseplanner.data.PlanOutcome
import com.swpp.licenseplanner.data.PlanStorageScope
import com.swpp.licenseplanner.data.PlanningRepository
import com.swpp.licenseplanner.data.ProfileOutcome
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
import com.swpp.licenseplanner.domain.ManualMoveRejection
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.StudyAvailability
import com.swpp.licenseplanner.domain.StudyCheckIn
import com.swpp.licenseplanner.domain.StudyCheckInStatus
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
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
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

    /** Adjustment never reads or saves the shared profile; any call fails the test. */
    private object UntouchedProfile : AvailabilityProfileRepository {
        override suspend fun load(): ProfileOutcome = throw AssertionError("profile loaded")
        override suspend fun save(availability: StudyAvailability): ProfileOutcome = throw AssertionError("profile saved")
    }

    private val mondayWindows = mapOf("monday" to listOf(TimeWindow("18:00", durationMinutes = 120)))
    private val wednesdayWindows = mapOf("wednesday" to listOf(TimeWindow("18:00", durationMinutes = 60)))

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

    /** A loaded, complete form with Monday 18:00–20:00 (plan A's windows) and a restored empty collection. */
    private fun TestScope.viewModel(planning: PlanningRepository, adjustment: AdjustmentRepository) = PlanFormViewModel(
        FakeQuestions(QuestionOutcome.Loaded(TestFixtures.publicQuestions())),
        planning,
        adjustment,
        LocalDate.of(2026, 10, 5),
        BytesPlanStore(MemoryPlanBytes(), PlanStorageScope.of(demo = false, apiBaseUrl = "http://test/api/v1"), dispatcher),
        availabilityProfileRepository = UntouchedProfile,
    ).apply {
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

    /** The saved result of moving [id] from its row in [sent] to its row in [returned]. */
    private fun savedMove(sent: List<ScheduleRow>, returned: List<ScheduleRow>, id: String = "study-1") =
        AdjustmentState.Success(id, SessionMove(sent.single { it.scheduleId == id }, returned.single { it.scheduleId == id }), MoveSave.Saved)

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
            assertEquals(savedMove(rowsA, movedA), vm.adjustment.value.state)
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
        assertEquals(AdjustmentSelection(1, null, savedMove(rowsA, movedA)), vm.adjustment.value)
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

    /** A plan the server cannot adjust without a stored exam date stays as it was; nothing is sent again. */
    @Test fun noStoredExamDateRejectionKeepsPlansAndSendsNothingMore() = runTest(dispatcher) {
        val error = ApiError.InvalidInput(
            "This saved plan has no stored exam date. Create a new plan before adjusting study sessions.",
        )
        val planning = FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false))
        val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(error))
        val vm = viewModel(planning, adjustment)
        generateAB(vm)
        val before = vm.plans.value
        vm.select(1, "study-1")
        vm.submitAdjustment()
        advanceUntilIdle()

        assertEquals(AdjustmentSelection(1, "study-1", AdjustmentState.Failure(error)), vm.adjustment.value)
        assertEquals(
            "이 계획에는 시험일 정보가 저장되어 있지 않아요.\n목록으로 돌아가 새 계획을 만든 뒤 다시 조정해 주세요.",
            error.adjustmentMessage(),
        )
        assertEquals(before, vm.plans.value)
        assertEquals(1, adjustment.inputs.size)
        assertTrue(adjustment.reloadIdentities.isEmpty())
        assertTrue(planning.queue.isEmpty())
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
            assertEquals(ApiJson.parseToJsonElement("""{"monday":[{"start":"18:00","duration_minutes":120}]}"""), sent["weekly_availability"])
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
        assertEquals(savedMove(rowsA, movedA), vm.adjustment.value.state)
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
            assertTrue(vm.generate()) // A with Monday and the 11/16 exam
            advanceUntilIdle()
            vm.switchToWednesday()
            vm.setExamDate(LocalDate.of(2026, 12, 14))
            assertTrue(vm.generate()) // B with Wednesday and a later exam
            advanceUntilIdle()
            assertEquals("2026/12/14/00/00", vm.plan(2).original.examDate)
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

    @Test fun demoNoSlotIsNotRecalculatedUntilAnotherSessionIsChosen() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.DemoNoSlot, AdjustmentOutcome.DemoNoSlot)
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, true)), adjustment)
        vm.generate()
        advanceUntilIdle()
        val before = vm.plans.value
        vm.select(1, "study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        val noSlot = AdjustmentSelection(1, "study-1", AdjustmentState.DemoNoSlot)

        // Repeated taps, retry, and reselecting the same row send nothing.
        assertFalse(vm.submitAdjustment())
        vm.retryAdjustment()
        vm.selectFailedSession("study-1")
        assertFalse(vm.submitAdjustment())
        vm.selectFailedSession("external-1")
        assertFalse(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(1, adjustment.inputs.size)
        assertEquals(noSlot, vm.adjustment.value)
        assertEquals(before, vm.plans.value)

        // A different study row is a new explicit attempt with the plan's original windows.
        vm.selectFailedSession("study-2")
        assertEquals(AdjustmentSelection(1, "study-2", AdjustmentState.Idle), vm.adjustment.value)
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(listOf("study-1", "study-2"), adjustment.inputs.map { it.scheduleId })
        assertEquals(mondayWindows, adjustment.inputs[1].original.weeklyAvailability)
        assertEquals(AdjustmentSelection(1, "study-2", AdjustmentState.DemoNoSlot), vm.adjustment.value)
        assertFalse(vm.submitAdjustment())
        assertEquals(2, adjustment.inputs.size)
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
            assertTrue(vm.adjustment.value.state is AdjustmentState.Failure) // no move is claimed
            assertEquals(rowsA, vm.plan(1).current.schedules)
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
            assertEquals(AdjustmentState.Reloaded, vm.adjustment.value.state) // a reload is not a move
            assertEquals(planBBefore, vm.plan(2))
            assertEquals(2, vm.plans.value.size)
            assertFalse(vm.submitAdjustment())
            assertEquals(3, server.requestCount)

            vm.switchToWednesday()
            vm.selectFailedSession("study-2")
            assertTrue(vm.submitAdjustment())
            advanceUntilIdle()
            assertEquals(final, vm.plan(1).current.schedules)
            assertEquals(savedMove(savedA, final, "study-2"), vm.adjustment.value.state) // from the reloaded rows

            val requests = server.requests()
            assertEquals(listOf("POST", "GET", "GET", "POST"), requests.map { it.method })
            val reloadA = "/api/v1/study-plans?schedule_csv_file=license_planner%2Foutput%2Fstudy_plan_a.csv"
            assertEquals(listOf("/api/v1/schedules/adjust", reloadA, reloadA, "/api/v1/schedules/adjust"), requests.map { it.path })
            val sent = ApiJson.parseToJsonElement(requests[3].body.readUtf8()).jsonObject
            assertEquals(setOf("schedule_csv_file", "schedule_id", "schedules", "weekly_availability", "exam_date", "availability_overrides"), sent.keys)
            assertEquals(ApiJson.parseToJsonElement("\"$fileA\""), sent["schedule_csv_file"])
            assertEquals(ApiJson.parseToJsonElement("\"study-2\""), sent["schedule_id"])
            assertEquals(ApiJson.parseToJsonElement(rowsJson(savedA)), sent["schedules"])
            assertEquals(ApiJson.parseToJsonElement("""{"monday":[{"start":"18:00","duration_minutes":120}]}"""), sent["weekly_availability"])
            assertEquals(ApiJson.parseToJsonElement("\"2026/11/16/00/00\""), sent["exam_date"])
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

    // --- Chosen-time (manual) moves ---

    private val chosen = LocalDateTime.of(2026, 10, 13, 14, 0)
    private val elsewhere = LocalDateTime.of(2026, 10, 14, 9, 0)

    /** A's study-1 at [chosen] with its one-hour duration and topic; every other row unchanged. */
    private val manualA = rowsA.map { if (it.scheduleId == "study-1") it.copy(startDate = "2026/10/13/14/00", endDate = "2026/10/13/15/00") else it }

    private fun TestScope.manualDraftOnA(adjustment: AdjustmentRepository): PlanFormViewModel {
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment)
        generateAB(vm)
        vm.select(1, "study-1")
        assertTrue(vm.startManualMove())
        vm.setManualStart(chosen)
        return vm
    }

    @Test fun manualMoveSendsOnlyTheReviewedDestinationOnce() = runTest(dispatcher) {
        val outside = AdjustmentOutcome.Failure(ApiError.ManualMoveRejected(ManualMoveRejection.OutsideStudyWindows))
        val adjustment = FakeAdjustment(outside, outside, AdjustmentOutcome.Success(StudyPlanResponse(manualA, "csv", null, null)))
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment)
        generateAB(vm)
        vm.select(1, "study-1")
        assertFalse(vm.submitManualMove()) // nothing opened
        assertTrue(vm.startManualMove())
        assertFalse(vm.submitManualMove()) // no destination yet
        vm.setManualStart(chosen)
        assertFalse(vm.confirmOutsideAvailability()) // not solicited yet
        vm.cancelManualMove()
        assertNull(vm.adjustment.value.manual)
        assertFalse(vm.submitManualMove())
        advanceUntilIdle()
        assertTrue(adjustment.inputs.isEmpty())

        assertTrue(vm.startManualMove())
        vm.setManualStart(chosen)
        adjustment.gate = CompletableDeferred()
        assertTrue(vm.submitManualMove())
        assertFalse(vm.submitManualMove())
        assertFalse(vm.confirmOutsideAvailability())
        assertFalse(vm.submitAdjustment())
        vm.setManualStart(elsewhere) // ignored while in flight
        advanceUntilIdle()
        adjustment.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, adjustment.inputs.size)
        val first = adjustment.inputs.single()
        assertEquals(ManualMove(chosen, confirmOutsideAvailability = false), first.manualMove)
        assertEquals(vm.plan(1).original, first.original)
        assertEquals(rowsA, first.schedules)
        assertEquals("study-1", first.scheduleId)
        assertTrue(vm.adjustment.value.canConfirmOutsideAvailability)
        assertEquals(ManualDraft(chosen), vm.adjustment.value.manual)
        assertEquals(rowsA, vm.plan(1).current.schedules)
        assertEquals(PlanRecovery.Ready, vm.plan(1).recovery)

        // The same refused destination is not resent without the explicit exception.
        assertFalse(vm.submitManualMove())
        vm.retryAdjustment()
        advanceUntilIdle()
        assertEquals(1, adjustment.inputs.size)

        // Another time clears the solicitation; it must be refused again before it can be confirmed.
        vm.setManualStart(elsewhere)
        assertFalse(vm.adjustment.value.canConfirmOutsideAvailability)
        assertFalse(vm.confirmOutsideAvailability())
        vm.setManualStart(chosen)
        assertFalse(vm.confirmOutsideAvailability())
        assertTrue(vm.submitManualMove())
        advanceUntilIdle()
        assertTrue(vm.confirmOutsideAvailability())
        assertFalse(vm.confirmOutsideAvailability())
        assertEquals(ManualDraft(chosen, confirmOutsideAvailability = true), vm.adjustment.value.manual)
        advanceUntilIdle()
        assertEquals(3, adjustment.inputs.size)
        assertEquals(ManualMove(chosen, confirmOutsideAvailability = true), adjustment.inputs[2].manualMove)
        assertEquals(first.copy(manualMove = ManualMove(chosen, true)), adjustment.inputs[2])
        assertEquals(AdjustmentSelection(1, null, savedMove(rowsA, manualA)), vm.adjustment.value)
        assertEquals(manualA, vm.plan(1).current.schedules)
        assertEquals(rowsB, vm.plan(2).current.schedules)
    }

    @Test fun onlyOutsideStudyWindowsCanBeConfirmedAndRefusalsStayDistinct() = runTest(dispatcher) {
        val messages = mutableSetOf<String>()
        for (reason in ManualMoveRejection.entries) {
            val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(ApiError.ManualMoveRejected(reason)))
            val vm = manualDraftOnA(adjustment)
            assertTrue(vm.submitManualMove())
            advanceUntilIdle()
            val selection = vm.adjustment.value
            assertEquals(AdjustmentState.Failure(ApiError.ManualMoveRejected(reason)), selection.state)
            assertEquals(ManualDraft(chosen), selection.manual) // the chosen time is kept
            assertEquals(PlanRecovery.Ready, vm.plan(1).recovery)
            assertFalse(ApiError.ManualMoveRejected(reason).requiresReload())
            assertEquals(reason == ManualMoveRejection.OutsideStudyWindows, selection.canConfirmOutsideAvailability)
            if (reason != ManualMoveRejection.OutsideStudyWindows) {
                assertFalse(vm.confirmOutsideAvailability())
                advanceUntilIdle()
                assertEquals(1, adjustment.inputs.size)
            }
            messages += ApiError.ManualMoveRejected(reason).adjustmentMessage()
        }
        assertEquals(ManualMoveRejection.entries.size, messages.size)
        // The generic invalid-input refusal never solicits the exception either.
        val vm = manualDraftOnA(FakeAdjustment(AdjustmentOutcome.Failure(ApiError.InvalidInput("bad"))))
        assertTrue(vm.submitManualMove())
        advanceUntilIdle()
        assertFalse(vm.adjustment.value.canConfirmOutsideAvailability)
    }

    @Test fun choosingAgainAfterTheWindowQuestionKeepsTheTimeAndSendsNothing() = runTest(dispatcher) {
        val outside = AdjustmentOutcome.Failure(ApiError.ManualMoveRejected(ManualMoveRejection.OutsideStudyWindows))
        val adjustment = FakeAdjustment(outside, outside)
        val vm = manualDraftOnA(adjustment)
        vm.reviseManualMove() // nothing to decline yet
        assertEquals(AdjustmentSelection(1, "study-1", manual = ManualDraft(chosen)), vm.adjustment.value)
        assertTrue(vm.submitManualMove())
        advanceUntilIdle()
        assertTrue(vm.adjustment.value.canConfirmOutsideAvailability)

        vm.reviseManualMove()
        assertEquals(AdjustmentSelection(1, "study-1", manual = ManualDraft(chosen)), vm.adjustment.value)
        assertFalse(vm.confirmOutsideAvailability())
        vm.retryAdjustment()
        advanceUntilIdle()
        assertEquals(1, adjustment.inputs.size)

        // Sending the same time again is a new, unconfirmed request that the server checks from the start.
        assertTrue(vm.submitManualMove())
        advanceUntilIdle()
        assertEquals(listOf(ManualMove(chosen), ManualMove(chosen)), adjustment.inputs.map { it.manualMove })
        assertTrue(vm.adjustment.value.canConfirmOutsideAvailability)
        assertEquals(rowsA, vm.plan(1).current.schedules)
    }

    @Test fun inFlightManualMoveCannotBeCancelledOrChangedAndRetryResendsTheSameAction() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(
            AdjustmentOutcome.Failure(ApiError.ServerFailure(500)),
            AdjustmentOutcome.Success(StudyPlanResponse(manualA, "csv", null, null)),
        )
        val vm = manualDraftOnA(adjustment)
        adjustment.gate = CompletableDeferred()
        assertTrue(vm.submitManualMove())
        vm.cancelManualMove()
        vm.reviseManualMove()
        vm.setManualStart(elsewhere)
        assertFalse(vm.selectPlan(2))
        assertEquals(AdjustmentState.Loading, vm.adjustment.value.state)
        assertEquals(ManualDraft(chosen), vm.adjustment.value.manual)
        adjustment.gate!!.complete(Unit)
        adjustment.gate = null
        advanceUntilIdle()

        // A definite server failure keeps the chosen time; retry resends exactly the reviewed action.
        assertEquals(AdjustmentState.Failure(ApiError.ServerFailure(500)), vm.adjustment.value.state)
        assertEquals(ManualDraft(chosen), vm.adjustment.value.manual)
        assertFalse(vm.adjustment.value.canConfirmOutsideAvailability)
        vm.retryAdjustment()
        advanceUntilIdle()
        assertEquals(2, adjustment.inputs.size)
        assertEquals(adjustment.inputs[0], adjustment.inputs[1])
        assertEquals(AdjustmentSelection(1, null, savedMove(rowsA, manualA)), vm.adjustment.value)
        assertEquals(manualA, vm.plan(1).current.schedules)
    }

    @Test fun changingTargetOrPlanClearsTheManualDraftAndConfirmation() = runTest(dispatcher) {
        val outside = AdjustmentOutcome.Failure(ApiError.ManualMoveRejected(ManualMoveRejection.OutsideStudyWindows))
        val adjustment = FakeAdjustment(outside, outside)
        val vm = manualDraftOnA(adjustment)
        assertTrue(vm.submitManualMove())
        advanceUntilIdle()
        assertTrue(vm.adjustment.value.canConfirmOutsideAvailability)

        vm.selectFailedSession("study-2")
        assertEquals(AdjustmentSelection(1, "study-2"), vm.adjustment.value)
        assertFalse(vm.confirmOutsideAvailability())

        vm.selectFailedSession("study-1")
        assertTrue(vm.startManualMove())
        vm.setManualStart(chosen)
        assertTrue(vm.submitManualMove())
        advanceUntilIdle()
        assertTrue(vm.adjustment.value.canConfirmOutsideAvailability)
        assertTrue(vm.selectPlan(2))
        assertTrue(vm.selectPlan(1))
        assertEquals(AdjustmentSelection(1), vm.adjustment.value)
        assertFalse(vm.confirmOutsideAvailability())
        advanceUntilIdle()
        assertEquals(2, adjustment.inputs.size)
        assertTrue(adjustment.inputs.all { it.manualMove == ManualMove(chosen, false) })
    }

    @Test fun liveManualConfirmationSendsTheCapturedDestinationAndFlag() = runTest(dispatcher) {
        val server = MockWebServer().apply { start() }
        try {
            val vm = manualDraftOnA(LiveAdjustmentRepository(server.url("/api/v1/").toString(), io = dispatcher))
            server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":{"code":"OUTSIDE_STUDY_WINDOWS","message":"x"}}"""))
            server.enqueue(MockResponse().setBody(adjustedJson(manualA, "study-1")))
            assertTrue(vm.submitManualMove())
            advanceUntilIdle()
            assertTrue(vm.confirmOutsideAvailability())
            advanceUntilIdle()
            assertEquals(manualA, vm.plan(1).current.schedules)
            assertEquals(mondayWindows, vm.plan(1).original.weeklyAvailability)

            val sent = server.requests().map { ApiJson.parseToJsonElement(it.body.readUtf8()).jsonObject }
            assertEquals(2, sent.size)
            for ((request, confirmed) in sent.zip(listOf(false, true))) {
                assertEquals(ApiJson.parseToJsonElement("\"manual\""), request["mode"])
                assertEquals(ApiJson.parseToJsonElement("\"2026/10/13/14/00\""), request["new_start_date"])
                assertEquals(ApiJson.parseToJsonElement("$confirmed"), request["confirm_outside_availability"])
                assertEquals(ApiJson.parseToJsonElement("\"study-1\""), request["schedule_id"])
                assertEquals(ApiJson.parseToJsonElement(rowsJson(rowsA)), request["schedules"])
                assertEquals(ApiJson.parseToJsonElement("""{"monday":[{"start":"18:00","duration_minutes":120}]}"""), request["weekly_availability"])
            }
            assertEquals(sent[0] - "confirm_outside_availability", sent[1] - "confirm_outside_availability")
        } finally {
            server.shutdown()
        }
    }

    @Test fun liveUncertainManualOutcomesRequireReloadWithoutReplay() = runTest(dispatcher) {
        fun moved(id: String, start: String, end: String, topic: String? = null) =
            manualA.map { if (it.scheduleId == id) it.copy(startDate = start, endDate = end, topic = topic ?: it.topic) else it }
        val failures = listOf(
            MockResponse().setResponseCode(409).setBody("""{"error":{"code":"PLAN_CONFLICT","message":"x"}}"""),
            MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
            MockResponse().setBody(adjustedJson(moved("study-1", "2026/10/13/16/00", "2026/10/13/17/00"), "study-1")),
            MockResponse().setBody(adjustedJson(moved("study-1", "2026/10/13/14/00", "2026/10/13/15/30"), "study-1")),
            MockResponse().setBody(adjustedJson(moved("study-1", "2026/10/13/14/00", "2026/10/13/15/00", "다른 주제"), "study-1")),
            MockResponse().setBody(adjustedJson(moved("study-2", "2026/10/20/19/00", "2026/10/20/20/00"), "study-1")),
        )
        for (failure in failures) {
            val server = MockWebServer().apply { start() }
            try {
                val vm = manualDraftOnA(LiveAdjustmentRepository(server.url("/api/v1/").toString(), io = dispatcher))
                val planBBefore = vm.plan(2)
                server.enqueue(failure)
                server.enqueue(MockResponse().setBody(listJson(fileA to rowsA)))
                assertTrue(vm.submitManualMove())
                advanceUntilIdle()
                val error = (vm.adjustment.value.state as AdjustmentState.Failure).error
                assertTrue(error.toString(), error.requiresReload())
                assertEquals(PlanRecovery.ReloadRequired, vm.plan(1).recovery)
                assertEquals(rowsA, vm.plan(1).current.schedules)
                assertEquals(planBBefore, vm.plan(2))
                assertNull(vm.adjustment.value.manual) // a fresh reviewed choice is needed
                assertFalse(vm.confirmOutsideAvailability())
                assertFalse(vm.submitManualMove())
                vm.retryAdjustment()
                assertFalse(vm.startManualMove())
                advanceUntilIdle()
                assertEquals(1, server.requestCount)

                assertTrue(vm.reloadPlan())
                advanceUntilIdle()
                assertEquals(AdjustmentSelection(1, null, AdjustmentState.Reloaded), vm.adjustment.value)
                assertFalse(vm.startManualMove()) // a session must be chosen again
                assertFalse(vm.submitManualMove())
                advanceUntilIdle()
                assertEquals(listOf("POST", "GET"), server.requests().map { it.method })
            } finally {
                server.shutdown()
            }
        }
    }

    @Test fun liveManualPlanNotFoundSendsNothingMore() = runTest(dispatcher) {
        val server = MockWebServer().apply { start() }
        try {
            val vm = manualDraftOnA(LiveAdjustmentRepository(server.url("/api/v1/").toString(), io = dispatcher))
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"PLAN_NOT_FOUND","message":"$fileA"}}"""))
            assertTrue(vm.submitManualMove())
            advanceUntilIdle()
            assertEquals(AdjustmentState.Failure(ApiError.PlanNotFound), vm.adjustment.value.state)
            assertEquals(PlanRecovery.Missing, vm.plan(1).recovery)
            assertNull(vm.adjustment.value.manual)
            assertFalse(vm.startManualMove())
            assertFalse(vm.submitManualMove())
            vm.retryAdjustment()
            assertFalse(vm.reloadPlan())
            advanceUntilIdle()
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    // --- Study reports ---

    private fun TestScope.report(vm: PlanFormViewModel, ordinal: Int, scheduleId: String, status: StudyCheckInStatus, minutes: String? = null) {
        assertTrue(vm.startCheckIn(ordinal, scheduleId))
        vm.chooseCheckInStatus(status)
        minutes?.let(vm::setCheckInMinutes)
        assertTrue(vm.saveCheckIn())
        advanceUntilIdle()
        vm.closeCheckIn()
    }

    @Test fun reportsFollowTheirRowsThroughAutomaticAndChosenMovesAndFailures() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(
            AdjustmentOutcome.Success(adjustedA),
            AdjustmentOutcome.Success(StudyPlanResponse(manualA, "csv", null, null)),
            AdjustmentOutcome.Failure(ApiError.Connection),
        )
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment)
        generateAB(vm)
        report(vm, 1, "study-1", StudyCheckInStatus.Partly, "45")
        report(vm, 1, "study-2", StudyCheckInStatus.Missed)
        report(vm, 2, "study-1", StudyCheckInStatus.Done, "20")
        assertTrue(adjustment.inputs.isEmpty()) // reporting requested no adjustment
        val reportsA = mapOf(
            "study-1" to StudyCheckIn(StudyCheckInStatus.Partly, 45),
            "study-2" to StudyCheckIn(StudyCheckInStatus.Missed, 0),
        )
        val b = vm.plan(2)
        assertEquals(mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Done, 20)), b.checkIns)

        vm.select(1, "study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(movedA, vm.plan(1).current.schedules)
        assertEquals(reportsA, vm.plan(1).checkIns)
        assertEquals(StudyCheckIn(StudyCheckInStatus.Partly, 45), vm.plan(1).checkInFor("study-1"))
        assertEquals(mondayWindows, adjustment.inputs[0].original.weeklyAvailability)

        vm.selectFailedSession("study-1")
        assertTrue(vm.startManualMove())
        vm.setManualStart(chosen)
        assertTrue(vm.submitManualMove())
        advanceUntilIdle()
        assertEquals(manualA, vm.plan(1).current.schedules)
        assertEquals(reportsA, vm.plan(1).checkIns)

        vm.selectFailedSession("study-2")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(PlanRecovery.ReloadRequired to "study-2", vm.plan(1).recovery to vm.plan(1).recoveryScheduleId)
        assertEquals(reportsA, vm.plan(1).checkIns)
        assertEquals(mondayWindows, vm.plan(1).original.weeklyAvailability)
        assertEquals(b, vm.plan(2))
        assertEquals(3, adjustment.inputs.size)
    }

    @Test fun chosenAutomaticAndFailedMovesKeepTheSavedDraftAndBothPlansReports() = runTest(dispatcher) {
        val movedB = rowsB.map { if (it.scheduleId == "study-1") it.copy(startDate = "2026/10/21/18/00", endDate = "2026/10/21/19/00") else it }
        val adjustment = FakeAdjustment(
            AdjustmentOutcome.Success(StudyPlanResponse(manualA, "csv", null, null)),
            AdjustmentOutcome.Failure(ApiError.PlanConflict),
            AdjustmentOutcome.Success(StudyPlanResponse(movedB, "csv-b2", null, null)),
        )
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment)
        generateAB(vm)
        report(vm, 1, "study-1", StudyCheckInStatus.Partly, "45")
        report(vm, 2, "study-1", StudyCheckInStatus.Done, "20")
        vm.setLevel(CurrentLevel.BEGINNER)
        assertTrue(vm.saveDraftAndExit())
        advanceUntilIdle()
        vm.draftDecisionHandled()
        val draft = vm.committedDraft.value!!
        val reportA = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Partly, 45))
        val reportB = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Done, 20))

        fun assertSavedWithDraft() {
            assertEquals(draft, vm.committedDraft.value)
            assertEquals(vm.plans.value, vm.savedPlans.value)
            assertEquals(reportA, vm.plan(1).checkIns)
            assertEquals(reportB, vm.plan(2).checkIns)
        }

        vm.select(1, "study-1")
        assertTrue(vm.startManualMove())
        vm.setManualStart(chosen)
        assertTrue(vm.submitManualMove())
        advanceUntilIdle()
        assertEquals(manualA, vm.plan(1).current.schedules)
        assertSavedWithDraft()

        vm.selectFailedSession("study-2")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(PlanRecovery.ReloadRequired to "study-2", vm.plan(1).recovery to vm.plan(1).recoveryScheduleId)
        assertSavedWithDraft()

        vm.select(2, "study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(movedB, vm.plan(2).current.schedules)
        assertEquals(PlanRecovery.ReloadRequired, vm.plan(1).recovery)
        assertSavedWithDraft()
        assertEquals(3, adjustment.inputs.size)
    }

    // --- Move feedback ---

    @Test fun moveFeedbackComesFromTheSentAndAcceptedRowsOfItsOwnPlan() = runTest(dispatcher) {
        val yearEnd = rowsB.map { if (it.scheduleId == "study-1") ScheduleRow("study-1", "2026/12/31/23/30", "2027/01/01/00/30", "셀 참조 심화") else it }
        val monthEnd = yearEnd.map { if (it.scheduleId == "study-1") it.copy(startDate = "2027/01/31/23/40", endDate = "2027/02/01/00/40") else it }
        val adjustment = FakeAdjustment(
            AdjustmentOutcome.Success(StudyPlanResponse(yearEnd, "csv-b2", "", null)),
            AdjustmentOutcome.Success(StudyPlanResponse(monthEnd, "csv-b3", "study-1을 10월 12일 18:00으로 옮겼습니다.", null)),
        )
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment)
        generateAB(vm)
        val planABefore = vm.plan(1)

        // A shares study-1, but only B's row moves; a blank summary does not matter.
        vm.select(2, "study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        val first = SessionMove(
            ScheduleRow("study-1", "2026/10/07/18/00", "2026/10/07/19/00", "셀 참조"),
            ScheduleRow("study-1", "2026/12/31/23/30", "2027/01/01/00/30", "셀 참조 심화"),
        )
        assertEquals(AdjustmentSelection(2, null, AdjustmentState.Success("study-1", first, MoveSave.Saved)), vm.adjustment.value)
        assertEquals("study-1", movedScheduleId(vm.plan(2), vm.adjustment.value.state))
        assertEquals(planABefore, vm.plan(1))

        // The next move starts from the moved row, not the generated one; the summary is not read.
        vm.selectFailedSession("study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        val second = SessionMove(first.after, ScheduleRow("study-1", "2027/01/31/23/40", "2027/02/01/00/40", "셀 참조 심화"))
        assertEquals(AdjustmentState.Success("study-1", second, MoveSave.Saved), vm.adjustment.value.state)
        assertEquals(yearEnd, adjustment.inputs[1].schedules)
        assertEquals(monthEnd, vm.plan(2).current.schedules)
        assertEquals(planABefore, vm.plan(1))
        assertEquals(2, adjustment.inputs.size)
    }

    @Test fun chosenTimeFeedbackShowsTheWholeOvernightInterval() = runTest(dispatcher) {
        val overnight = rowsA.map { if (it.scheduleId == "study-1") it.copy(startDate = "2026/10/31/23/30", endDate = "2026/11/01/00/30") else it }
        val adjustment = FakeAdjustment(AdjustmentOutcome.Success(StudyPlanResponse(overnight, "csv", "요약 A", null)))
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment)
        generateAB(vm)
        vm.select(1, "study-1")
        assertTrue(vm.startManualMove())
        vm.setManualStart(LocalDateTime.of(2026, 10, 31, 23, 30))
        assertTrue(vm.submitManualMove())
        advanceUntilIdle()

        val move = SessionMove(
            ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수"),
            ScheduleRow("study-1", "2026/10/31/23/30", "2026/11/01/00/30", "함수"),
        )
        assertEquals(AdjustmentSelection(1, null, AdjustmentState.Success("study-1", move, MoveSave.Saved)), vm.adjustment.value)
        assertEquals(rowsB, vm.plan(2).current.schedules)
        assertEquals(1, adjustment.inputs.size)
    }

    @Test fun moveFeedbackStaysUntilAnotherSelectionOrReopening() = runTest(dispatcher) {
        val movedTwice = movedA.map { if (it.scheduleId == "study-2") it.copy(startDate = "2026/10/12/19/00", endDate = "2026/10/12/20/00") else it }
        val adjustment = FakeAdjustment(
            AdjustmentOutcome.Success(adjustedA),
            AdjustmentOutcome.Success(StudyPlanResponse(movedTwice, "csv", null, null)),
            AdjustmentOutcome.Success(StudyPlanResponse(movedTwice, "csv", null, null)),
        )
        val vm = viewModel(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment)
        generateAB(vm)
        vm.select(1, "study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        val shown = AdjustmentSelection(1, null, savedMove(rowsA, movedA))
        assertEquals(shown, vm.adjustment.value)

        // A report on the same plan is a separate change and leaves the shown move alone.
        report(vm, 1, "study-1", StudyCheckInStatus.Done, "60")
        assertEquals(shown, vm.adjustment.value)

        // Choosing another session clears it without sending anything.
        vm.selectFailedSession("study-2")
        assertEquals(AdjustmentSelection(1, "study-2"), vm.adjustment.value)
        assertEquals(1, adjustment.inputs.size)
        assertEquals(movedA, vm.plan(1).current.schedules)

        // Reopening the same plan clears it, and so does leaving for another plan.
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(savedMove(movedA, movedTwice, "study-2"), vm.adjustment.value.state)
        assertTrue(vm.selectPlan(1))
        assertEquals(AdjustmentSelection(1), vm.adjustment.value)
        assertNull(movedScheduleId(vm.plan(1), vm.adjustment.value.state))
        vm.selectFailedSession("study-2")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertTrue(vm.adjustment.value.state is AdjustmentState.Success)
        assertTrue(vm.selectPlan(2))
        assertTrue(vm.selectPlan(1))
        assertEquals(AdjustmentSelection(1), vm.adjustment.value)
        assertEquals(movedTwice, vm.plan(1).current.schedules)
        assertEquals(mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Done, 60)), vm.plan(1).checkIns)
        assertEquals(3, adjustment.inputs.size)
    }
}
