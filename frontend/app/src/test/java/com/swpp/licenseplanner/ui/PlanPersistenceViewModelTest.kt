package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentOutcome
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.ApiJson
import com.swpp.licenseplanner.data.BytesPlanStore
import com.swpp.licenseplanner.data.LiveAdjustmentRepository
import com.swpp.licenseplanner.data.LocalPlanStore
import com.swpp.licenseplanner.data.MemoryPlanBytes
import com.swpp.licenseplanner.data.PlanOutcome
import com.swpp.licenseplanner.data.PlanSnapshot
import com.swpp.licenseplanner.data.PlanStorageScope
import com.swpp.licenseplanner.data.PlanStoreLoad
import com.swpp.licenseplanner.data.PlanningRepository
import com.swpp.licenseplanner.data.QuestionOutcome
import com.swpp.licenseplanner.data.QuestionRepository
import com.swpp.licenseplanner.data.ReloadOutcome
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.Weekday
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/**
 * Saving and restoring the plan collection. A restart is a new view model over the same
 * committed bytes; nothing else survives it. This proves ordering, not Android file durability.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlanPersistenceViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val liveScope = PlanStorageScope.of(demo = false, apiBaseUrl = "http://test/api/v1")

    /** The device's committed bytes, shared by every view model of one test. */
    private val disk = MemoryPlanBytes()

    /** Saves and adjustment calls in the order they happened. */
    private val events = mutableListOf<String>()

    private class FakeQuestions : QuestionRepository {
        override suspend fun load(certificationId: String) = QuestionOutcome.Loaded(TestFixtures.publicQuestions())
    }

    private class FakePlanning(vararg replies: PlanOutcome) : PlanningRepository {
        val queue = ArrayDeque(replies.toList())
        val requests = mutableListOf<StudyPlanRequest>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun generate(request: StudyPlanRequest): PlanOutcome {
            requests += request
            gate?.await()
            return queue.removeFirst()
        }
    }

    private inner class FakeAdjustment(vararg replies: AdjustmentOutcome) : AdjustmentRepository {
        val queue = ArrayDeque(replies.toList())
        val inputs = mutableListOf<AdjustmentInput>()
        var gate: CompletableDeferred<Unit>? = null
        val reloads = ArrayDeque<ReloadOutcome>()
        var reloadCalls = 0
        var reloadGate: CompletableDeferred<Unit>? = null
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
            inputs += input
            events += "adjust ${input.scheduleId}"
            gate?.await()
            return queue.removeFirst()
        }
        override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome {
            reloadCalls++
            events += "reload"
            reloadGate?.await()
            return reloads.removeFirst()
        }
    }

    /** The real byte store with a load gate, per-save gates and failures (1-based save numbers). */
    private inner class ControlledStore(scope: String) : LocalPlanStore {
        private val real = BytesPlanStore(disk, scope, dispatcher)
        var loadGate: CompletableDeferred<Unit>? = null
        val saveGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val failingSaves = mutableSetOf<Int>()
        var saves = 0
            private set

        override suspend fun load(): PlanStoreLoad {
            loadGate?.await()
            return real.load()
        }

        override suspend fun save(snapshot: PlanSnapshot): Boolean {
            val number = ++saves
            events += snapshot.inFlightAdjustmentOrdinal?.let { "save unresolved $it" } ?: "save"
            saveGates[number]?.await()
            return number !in failingSaves && real.save(snapshot)
        }
    }

    private val mondayWindows = mapOf("monday" to listOf(TimeWindow("18:00", "20:00")))
    private val wednesdayWindows = mapOf("wednesday" to listOf(TimeWindow("18:00", "19:00")))
    private val fileA = "license_planner/output/study_plan_a.csv"
    private val fileB = "license_planner/output/study_plan_b.csv"
    private val rowsA = listOf(
        ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수"),
        ScheduleRow("study-2", "2026/10/12/18/00", "2026/10/12/19/00", "차트"),
        ScheduleRow("external-1", "2026/10/06/18/00", "2026/10/06/19/00", ""),
    )
    private val rowsB = listOf(
        ScheduleRow("study-1", "2026/10/07/18/00", "2026/10/07/19/00", "셀 참조"),
        ScheduleRow("study-2", "2026/10/14/18/00", "2026/10/14/19/00", "셀 참조"),
    )
    private val planA = StudyPlanResponse(rowsA, "csv-a", "요약 A", fileA)
    private val planB = StudyPlanResponse(rowsB, "csv-b", "요약 B", fileB)
    private val movedA = rowsA.map { if (it.scheduleId == "study-1") it.copy(startDate = "2026/10/19/18/00", endDate = "2026/10/19/19/00") else it }
    private val adjustedA = StudyPlanResponse(movedA, "csv-a2", null, null)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /**
     * Starts a process over [disk]: restores (unless [configure] holds the load) and fills a
     * complete form with Monday 18:00–20:00.
     */
    private fun TestScope.open(
        planning: PlanningRepository = FakePlanning(),
        adjustment: AdjustmentRepository = FakeAdjustment(),
        scope: String = liveScope,
        configure: ControlledStore.() -> Unit = {},
    ): Pair<PlanFormViewModel, ControlledStore> {
        val store = ControlledStore(scope).apply(configure)
        val vm = PlanFormViewModel(FakeQuestions(), planning, adjustment, LocalDate.of(2026, 10, 5), store).apply {
            selectCertification(CERTIFICATION_ID)
            setExamDate(LocalDate.of(2026, 11, 16))
            setLevel(CurrentLevel.INTERMEDIATE)
            loadQuestions()
            advanceUntilIdle()
            form.value.questions.forEach { answer(it.id, it.correctAnswer) }
            setDayEnabled(Weekday.MONDAY, true)
            stepDuration(Weekday.MONDAY, 2)
        }
        return vm to store
    }

    private fun PlanFormViewModel.switchToWednesday() {
        setDayEnabled(Weekday.MONDAY, false)
        setDayEnabled(Weekday.WEDNESDAY, true)
    }

    /** Generates A (Monday) then B (Wednesday); both are saved. */
    private fun TestScope.generateAB(vm: PlanFormViewModel) {
        assertTrue(vm.generate())
        advanceUntilIdle()
        vm.switchToWednesday()
        assertTrue(vm.generate())
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, vm.storage.value)
    }

    private fun PlanFormViewModel.plan(ordinal: Int) = plans.value.single { it.ordinal == ordinal }

    private fun PlanFormViewModel.select(ordinal: Int, scheduleId: String) {
        assertTrue(selectPlan(ordinal))
        selectFailedSession(scheduleId)
    }

    /** What a new process would restore from [disk] right now. */
    private fun TestScope.restoredNow(scope: String = liveScope): List<SessionPlan> {
        val store = BytesPlanStore(MemoryPlanBytes(disk.committed), scope, dispatcher)
        var result: PlanStoreLoad? = null
        launch { result = store.load() }
        advanceUntilIdle()
        return (result as PlanStoreLoad.Loaded).snapshot.plans
    }

    private fun rowsJson(rows: List<ScheduleRow>) = ApiJson.encodeToString(ListSerializer(ScheduleRow.serializer()), rows)

    private fun adjustedJson(rows: List<ScheduleRow>, target: String) =
        """{"schedules":${rowsJson(rows)},"schedule_csv":"x","action":"rescheduled_study","target_schedule_id":"$target","schedule_csv_file":"$fileA"}"""

    private fun listJson(file: String, rows: List<ScheduleRow>) =
        """{"plans":[{"schedule_csv_file":"$file","schedule_csv_url":"/x.csv","schedules":${rowsJson(rows)}}]}"""

    // --- Restoring and appending ---

    @Test fun restartRestoresPlansInOrderAndAppendsWithANewOrdinal() = runTest(dispatcher) {
        val sameDates = planB.copy(schedules = rowsA.map { it.copy(topic = "다른 주제") }) // same IDs as A
        val (first, _) = open(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(sameDates, false)))
        generateAB(first)
        val saved = first.plans.value

        val planning = FakePlanning(PlanOutcome.Success(planB, false))
        val adjustment = FakeAdjustment()
        val (second, _) = open(planning, adjustment)
        assertEquals(PlanStorage.Ready, second.storage.value)
        assertEquals(saved, second.plans.value)
        assertEquals(mondayWindows, second.plan(1).original.weeklyAvailability)
        assertEquals(wednesdayWindows, second.plan(2).original.weeklyAvailability)
        assertEquals(AdjustmentSelection(), second.adjustment.value)
        assertNull(second.generatedPlan.value)
        assertTrue(planning.requests.isEmpty() && adjustment.inputs.isEmpty() && adjustment.reloadCalls == 0)

        second.switchToWednesday()
        second.stepDuration(Weekday.WEDNESDAY, 1)
        assertTrue(second.generate())
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), second.plans.value.map { it.ordinal })
        assertEquals(saved, second.plans.value.take(2)) // form edits never touch A or B
        assertEquals(3, second.generatedPlan.value)

        val (third, _) = open()
        assertEquals(second.plans.value, third.plans.value)
    }

    @Test fun restartRestoresAcceptedUpdatesRecoveryAndMissingStates() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(
            AdjustmentOutcome.Success(adjustedA),
            AdjustmentOutcome.Failure(ApiError.PlanNotFound),
            AdjustmentOutcome.Failure(ApiError.Connection),
            AdjustmentOutcome.Failure(ApiError.PlanConflict),
        )
        val (first, _) = open(
            FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false), PlanOutcome.Success(planB, false), PlanOutcome.Success(planA, false)),
            adjustment,
        )
        generateAB(first)
        repeat(2) { assertTrue(first.generate()); advanceUntilIdle() }
        first.select(1, "study-1")
        assertTrue(first.submitAdjustment())
        advanceUntilIdle()
        first.select(2, "study-1")
        assertTrue(first.submitAdjustment())
        advanceUntilIdle()
        first.select(3, "study-2")
        assertTrue(first.submitAdjustment())
        advanceUntilIdle()
        first.select(4, "study-2")
        assertTrue(first.submitAdjustment())
        advanceUntilIdle()
        adjustment.reloads += ReloadOutcome.Loaded(movedA)
        assertTrue(first.reloadPlan())
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, first.storage.value)

        val fresh = FakeAdjustment()
        val (second, _) = open(adjustment = fresh)
        val plans = second.plans.value
        assertEquals(first.plans.value, plans)
        assertEquals(movedA, plans[0].current.schedules) // the accepted adjustment
        assertEquals(PlanRecovery.Missing, plans[1].recovery)
        assertEquals(PlanRecovery.ReloadRequired to "study-2", plans[2].recovery to plans[2].recoveryScheduleId)
        assertEquals(PlanRecovery.Ready to null, plans[3].recovery to plans[3].recoveryScheduleId) // reloaded
        assertEquals(movedA, plans[3].current.schedules)
        assertEquals(mondayWindows, plans[0].original.weeklyAvailability)

        // No outcome, selection or request comes back; a missing plan stays unavailable.
        assertTrue(second.selectPlan(1))
        assertEquals(AdjustmentSelection(1), second.adjustment.value)
        assertTrue(second.selectPlan(2))
        second.selectFailedSession("study-1")
        assertFalse(second.submitAdjustment())
        assertFalse(second.reloadPlan())
        assertTrue(second.selectPlan(3))
        assertEquals(AdjustmentSelection(3, "study-2"), second.adjustment.value)
        assertFalse(second.submitAdjustment())
        second.retryAdjustment()
        assertTrue(second.selectPlan(4))
        assertEquals(AdjustmentSelection(4), second.adjustment.value)
        advanceUntilIdle()
        assertTrue(fresh.inputs.isEmpty())
        assertEquals(0, fresh.reloadCalls)
    }

    // --- Restore lifecycle ---

    @Test fun pendingRestoreBlocksPlanChangesUntilReady() = runTest(dispatcher) {
        val (first, _) = open(FakePlanning(PlanOutcome.Success(planA, false)))
        assertTrue(first.generate())
        advanceUntilIdle()

        val planning = FakePlanning(PlanOutcome.Success(planB, false))
        val gate = CompletableDeferred<Unit>()
        val (second, _) = open(planning) { loadGate = gate }
        assertEquals(PlanStorage.Loading, second.storage.value)
        assertTrue(second.plans.value.isEmpty())
        assertTrue(second.form.value.availability.getValue(Weekday.MONDAY).enabled) // form edits work
        assertFalse(second.generate())
        assertFalse(second.selectPlan(1))
        advanceUntilIdle()
        assertTrue(planning.requests.isEmpty())

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, second.storage.value)
        assertEquals(first.plans.value, second.plans.value)
        assertTrue(second.generate())
        advanceUntilIdle()
        assertEquals(listOf(1, 2), second.plans.value.map { it.ordinal })
    }

    @Test fun unreadableSavedPlansBlockChangesAndRetryReadsOnlyLocally() = runTest(dispatcher) {
        val (first, _) = open(FakePlanning(PlanOutcome.Success(planA, false)))
        assertTrue(first.generate())
        advanceUntilIdle()
        val good = disk.committed!!
        val corrupt = good.copyOf(good.size - 10)
        disk.committed = corrupt
        val writesBefore = disk.writes

        val planning = FakePlanning(PlanOutcome.Success(planB, false))
        val adjustment = FakeAdjustment()
        val (second, store) = open(planning, adjustment)
        assertEquals(PlanStorage.LoadFailure, second.storage.value)
        assertTrue(second.plans.value.isEmpty())
        assertFalse(second.generate())
        second.retryGeneration()
        second.retrySave()
        second.retryLoad()
        advanceUntilIdle()
        assertEquals(PlanStorage.LoadFailure, second.storage.value)
        assertTrue(planning.requests.isEmpty() && adjustment.inputs.isEmpty() && adjustment.reloadCalls == 0)
        assertEquals(0, store.saves)
        assertEquals(writesBefore, disk.writes)
        assertArrayEquals(corrupt, disk.committed)

        disk.committed = good // e.g. storage became readable again
        second.retryLoad()
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, second.storage.value)
        assertEquals(first.plans.value, second.plans.value)
        assertTrue(planning.requests.isEmpty())
    }

    // --- Saving after generation ---

    @Test fun failedSaveKeepsOneCandidateAndRetryOnlySaves() = runTest(dispatcher) {
        val planning = FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false), PlanOutcome.Success(planB, false))
        val adjustment = FakeAdjustment()
        val (vm, _) = open(planning, adjustment)
        assertTrue(vm.generate())
        advanceUntilIdle()
        val savedA = disk.committed

        disk.failWrites = true
        vm.switchToWednesday()
        assertTrue(vm.generate())
        advanceUntilIdle()
        assertEquals(GenerationState.Success(planB, false), vm.generation.value)
        assertEquals(PlanStorage.SaveFailure, vm.storage.value)
        assertEquals(listOf(1, 2), vm.plans.value.map { it.ordinal })
        assertArrayEquals(savedA, disk.committed)
        assertEquals(listOf(1), restoredNow().map { it.ordinal })

        // Viewing works; nothing that changes plans or sends a request does.
        assertTrue(vm.selectPlan(2))
        vm.selectFailedSession("study-1")
        assertFalse(vm.submitAdjustment())
        assertFalse(vm.generate())
        vm.retryGeneration()
        vm.retrySave() // fails again: still one candidate
        advanceUntilIdle()
        assertEquals(PlanStorage.SaveFailure, vm.storage.value)
        assertEquals(2, planning.requests.size)

        disk.failWrites = false
        vm.retrySave()
        vm.retrySave() // a second tap while saving does nothing
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, vm.storage.value)
        assertEquals(2, planning.requests.size)
        assertTrue(adjustment.inputs.isEmpty())
        assertEquals(vm.plans.value, restoredNow())
        assertTrue(vm.generate())
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 3), vm.plans.value.map { it.ordinal })
    }

    @Test fun formEditsFailuresAndSelectionWriteNothing() = runTest(dispatcher) {
        val (vm, store) = open(
            FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Failure(ApiError.ServerFailure(502)), PlanOutcome.DemoShortfall(12, 3)),
        )
        assertTrue(vm.generate())
        advanceUntilIdle()
        val saved = disk.committed
        val saves = store.saves

        vm.switchToWednesday()
        assertTrue(vm.generate())
        advanceUntilIdle()
        vm.stepDuration(Weekday.WEDNESDAY, 1)
        advanceUntilIdle()
        assertTrue(vm.generate())
        advanceUntilIdle()
        vm.select(1, "study-2")
        assertTrue(vm.selectPlan(1))
        advanceUntilIdle()

        assertEquals(saves, store.saves)
        assertArrayEquals(saved, disk.committed)
        assertEquals(1, vm.plans.value.size)
    }

    @Test fun generationInFlightWhenTheProcessEndsAddsNothing() = runTest(dispatcher) {
        val planning = FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false), PlanOutcome.Success(planB, false))
        val (first, _) = open(planning)
        generateAB(first)
        planning.gate = CompletableDeferred()
        first.stepDuration(Weekday.WEDNESDAY, 1)
        assertTrue(first.generate())
        advanceUntilIdle()

        val again = FakePlanning()
        val (second, _) = open(again)
        assertEquals(listOf(1, 2), second.plans.value.map { it.ordinal })
        assertEquals(GenerationState.Idle, second.generation.value)
        assertTrue(again.requests.isEmpty())
    }

    // --- Live adjustment ordering ---

    @Test fun liveAdjustmentIsSentOnlyAfterItsUnresolvedSnapshotIsSaved() = runTest(dispatcher) {
        val server = MockWebServer().apply { start() }
        try {
            val live = LiveAdjustmentRepository(server.url("/api/v1/").toString(), io = dispatcher)
            val (vm, store) = open(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), live)
            generateAB(vm)
            val markerGate = CompletableDeferred<Unit>()
            store.saveGates[store.saves + 1] = markerGate
            server.enqueue(MockResponse().setBody(adjustedJson(movedA, "study-1")))

            vm.select(1, "study-1")
            assertTrue(vm.submitAdjustment())
            assertFalse(vm.submitAdjustment())
            advanceUntilIdle()
            assertEquals(0, server.requestCount)
            assertEquals(AdjustmentState.Loading, vm.adjustment.value.state)
            assertEquals(PlanStorage.Saving, vm.storage.value)
            assertEquals(PlanRecovery.Ready, vm.plan(1).recovery) // the saved gate is not shown yet
            assertFalse(vm.submitAdjustment())
            vm.retryAdjustment()
            assertFalse(vm.reloadPlan())
            assertFalse(vm.generate())
            assertFalse(vm.selectPlan(2))
            assertEquals(listOf(PlanRecovery.Ready, PlanRecovery.Ready), restoredNow().map { it.recovery })

            markerGate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, server.requestCount)
            assertEquals(AdjustmentState.Success("study-1"), vm.adjustment.value.state)
            assertEquals(PlanStorage.Ready, vm.storage.value)
            assertEquals(listOf("save", "save", "save unresolved 1", "save"), events)
            assertEquals(vm.plans.value, restoredNow())
            assertNull(restoredNow()[0].recoveryScheduleId)
        } finally {
            server.shutdown()
        }
    }

    @Test fun processEndingDuringAnAdjustmentRestoresAReloadGateWithoutReplay() = runTest(dispatcher) {
        // The server may or may not have saved the change; either way only an explicit reload follows.
        for (savedOnServer in listOf(true, false)) {
            disk.committed = null
            val adjustment = FakeAdjustment(AdjustmentOutcome.Success(adjustedA)).apply { gate = CompletableDeferred() }
            val (first, _) = open(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment)
            generateAB(first)
            val planBBefore = first.plan(2)
            first.select(1, "study-2")
            assertTrue(first.submitAdjustment())
            advanceUntilIdle()
            assertEquals(1, adjustment.inputs.size) // dispatched, no answer yet

            val server = MockWebServer().apply { start() }
            try {
                val serverRows = if (savedOnServer) movedA else rowsA
                server.enqueue(MockResponse().setBody(listJson(fileA, serverRows)))
                val final = serverRows.map { if (it.scheduleId == "study-1") it.copy(startDate = "2026/10/26/18/00", endDate = "2026/10/26/19/00") else it }
                server.enqueue(MockResponse().setBody(adjustedJson(final, "study-1")))
                val (second, _) = open(adjustment = LiveAdjustmentRepository(server.url("/api/v1/").toString(), io = dispatcher))
                val restored = second.plan(1)
                assertEquals(PlanRecovery.ReloadRequired, restored.recovery)
                assertEquals("study-2", restored.recoveryScheduleId)
                assertEquals(rowsA, restored.current.schedules) // the pre-request copy
                assertEquals(planBBefore, second.plan(2))
                assertTrue(second.selectPlan(1))
                assertEquals(AdjustmentSelection(1, "study-2"), second.adjustment.value)
                second.retryAdjustment()
                second.selectFailedSession("study-1")
                assertFalse(second.submitAdjustment())
                advanceUntilIdle()
                assertEquals(0, server.requestCount)

                assertTrue(second.reloadPlan())
                advanceUntilIdle()
                assertEquals(AdjustmentSelection(1, null, AdjustmentState.Reloaded), second.adjustment.value)
                assertEquals(serverRows, second.plan(1).current.schedules)
                assertNull(second.plan(1).recoveryScheduleId)
                assertFalse(second.submitAdjustment()) // a fresh selection is needed
                second.selectFailedSession("study-1")
                assertTrue(second.submitAdjustment())
                advanceUntilIdle()

                val requests = List(server.requestCount) { server.takeRequest() }
                assertEquals(listOf("GET", "POST"), requests.map { it.method })
                val sent = ApiJson.parseToJsonElement(requests[1].body.readUtf8()).jsonObject
                assertEquals(ApiJson.parseToJsonElement("\"$fileA\""), sent["schedule_csv_file"])
                assertEquals(ApiJson.parseToJsonElement(rowsJson(serverRows)), sent["schedules"])
                assertEquals(ApiJson.parseToJsonElement("""{"monday":[{"start":"18:00","end":"20:00"}]}"""), sent["weekly_availability"])
                assertEquals(second.plans.value, restoredNow())
            } finally {
                server.shutdown()
            }
        }
    }

    @Test fun failedUnresolvedSaveSendsNothingAndRetrySavesWithoutResubmitting() = runTest(dispatcher) {
        val server = MockWebServer().apply { start() }
        try {
            val live = LiveAdjustmentRepository(server.url("/api/v1/").toString(), io = dispatcher)
            val (vm, store) = open(FakePlanning(PlanOutcome.Success(planA, false)), live)
            assertTrue(vm.generate())
            advanceUntilIdle()
            val before = vm.plans.value
            store.failingSaves += store.saves + 1

            vm.select(1, "study-1")
            assertTrue(vm.submitAdjustment())
            advanceUntilIdle()
            assertEquals(0, server.requestCount)
            assertEquals(PlanStorage.SaveFailure, vm.storage.value)
            assertEquals(AdjustmentSelection(1, "study-1"), vm.adjustment.value)
            assertEquals(before, vm.plans.value)
            assertFalse(vm.submitAdjustment())
            vm.retryAdjustment()

            vm.retrySave()
            advanceUntilIdle()
            assertEquals(PlanStorage.Ready, vm.storage.value)
            assertEquals(0, server.requestCount) // the save retry never submits
            assertEquals(listOf("save", "save unresolved 1", "save"), events)
            assertEquals(before, restoredNow())

            server.enqueue(MockResponse().setBody(adjustedJson(movedA, "study-1")))
            assertTrue(vm.submitAdjustment()) // a new explicit submission
            advanceUntilIdle()
            assertEquals(1, server.requestCount)
            assertEquals(movedA, vm.plan(1).current.schedules)
        } finally {
            server.shutdown()
        }
    }

    @Test fun failedOutcomeSaveKeepsTheResultAndTheDurableGate() = runTest(dispatcher) {
        val server = MockWebServer().apply { start() }
        try {
            val live = LiveAdjustmentRepository(server.url("/api/v1/").toString(), io = dispatcher)
            val (vm, store) = open(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), live)
            generateAB(vm)
            val planBBefore = vm.plan(2)
            store.failingSaves += store.saves + 2 // the save after the response
            server.enqueue(MockResponse().setBody(adjustedJson(movedA, "study-1")))

            vm.select(1, "study-1")
            assertTrue(vm.submitAdjustment())
            advanceUntilIdle()
            assertEquals(AdjustmentState.Success("study-1"), vm.adjustment.value.state)
            assertEquals(movedA, vm.plan(1).current.schedules)
            assertEquals(PlanStorage.SaveFailure, vm.storage.value)
            val ifRestartedNow = restoredNow()
            assertEquals(PlanRecovery.ReloadRequired to "study-1", ifRestartedNow[0].recovery to ifRestartedNow[0].recoveryScheduleId)
            assertEquals(rowsA, ifRestartedNow[0].current.schedules)
            assertEquals(planBBefore, ifRestartedNow[1])

            vm.selectFailedSession("study-2")
            assertFalse(vm.submitAdjustment())
            assertFalse(vm.generate())
            vm.retrySave()
            advanceUntilIdle()
            assertEquals(PlanStorage.Ready, vm.storage.value)
            assertEquals(1, server.requestCount)
            val resolved = restoredNow()
            assertEquals(vm.plans.value, resolved)
            assertEquals(PlanRecovery.Ready to null, resolved[0].recovery to resolved[0].recoveryScheduleId)
            assertEquals(movedA, resolved[0].current.schedules)
        } finally {
            server.shutdown()
        }
    }

    @Test fun processEndingBeforeTheOutcomeIsSavedRestoresTheGate() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Success(adjustedA))
        val (first, store) = open(FakePlanning(PlanOutcome.Success(planA, false)), adjustment)
        assertTrue(first.generate())
        advanceUntilIdle()
        store.saveGates[store.saves + 2] = CompletableDeferred() // the response arrived; its save never finishes
        first.select(1, "study-1")
        assertTrue(first.submitAdjustment())
        advanceUntilIdle()
        assertEquals(movedA, first.plan(1).current.schedules)
        assertEquals(PlanStorage.Saving, first.storage.value)

        val fresh = FakeAdjustment()
        val (second, _) = open(adjustment = fresh)
        assertEquals(PlanRecovery.ReloadRequired to "study-1", second.plan(1).recovery to second.plan(1).recoveryScheduleId)
        assertEquals(rowsA, second.plan(1).current.schedules)
        advanceUntilIdle()
        assertTrue(fresh.inputs.isEmpty())
        assertEquals(0, fresh.reloadCalls)
    }

    @Test fun confirmedFailuresAreSavedAsReadyWithoutTheRetry() = runTest(dispatcher) {
        for (error in listOf(ApiError.InvalidInput("x"), ApiError.ServerUnavailable("busy"))) {
            disk.committed = null
            events.clear()
            val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(error), AdjustmentOutcome.Failure(error))
            val (first, _) = open(FakePlanning(PlanOutcome.Success(planA, false)), adjustment)
            assertTrue(first.generate())
            advanceUntilIdle()
            val before = first.plan(1)
            first.select(1, "study-1")
            assertTrue(first.submitAdjustment())
            advanceUntilIdle()
            first.retryAdjustment() // an explicit retry is recorded as unresolved again first
            advanceUntilIdle()
            assertEquals(
                listOf("save", "save unresolved 1", "adjust study-1", "save", "save unresolved 1", "adjust study-1", "save"),
                events,
            )
            assertEquals(AdjustmentState.Failure(error), first.adjustment.value.state)

            val fresh = FakeAdjustment()
            val (second, _) = open(adjustment = fresh)
            assertEquals(listOf(before), second.plans.value)
            assertEquals(mondayWindows, second.plan(1).original.weeklyAvailability)
            assertTrue(second.selectPlan(1))
            assertEquals(AdjustmentSelection(1), second.adjustment.value)
            second.retryAdjustment()
            advanceUntilIdle()
            assertTrue(fresh.inputs.isEmpty())
        }
    }

    @Test fun processEndingDuringAReloadKeepsTheGateWithoutAutomaticGet() = runTest(dispatcher) {
        val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(ApiError.PlanConflict)).apply {
            reloadGate = CompletableDeferred()
            reloads += ReloadOutcome.Loaded(movedA)
        }
        val (first, _) = open(FakePlanning(PlanOutcome.Success(planA, false)), adjustment)
        assertTrue(first.generate())
        advanceUntilIdle()
        first.select(1, "study-2")
        assertTrue(first.submitAdjustment())
        advanceUntilIdle()
        assertTrue(first.reloadPlan())
        advanceUntilIdle()
        assertEquals(1, adjustment.reloadCalls)

        val fresh = FakeAdjustment()
        val (second, _) = open(adjustment = fresh)
        assertEquals(PlanRecovery.ReloadRequired to "study-2", second.plan(1).recovery to second.plan(1).recoveryScheduleId)
        assertEquals(rowsA, second.plan(1).current.schedules)
        assertTrue(second.selectPlan(1))
        assertEquals(AdjustmentSelection(1, "study-2"), second.adjustment.value)
        advanceUntilIdle()
        assertEquals(0, fresh.reloadCalls)
        assertTrue(fresh.inputs.isEmpty())
    }

    // --- Demo ---

    @Test fun demoAdjustmentsSaveNoUnresolvedStateAndRestoreTheLastCommit() = runTest(dispatcher) {
        val demoA = planA.copy(scheduleCsvFile = null)
        val adjustment = FakeAdjustment(AdjustmentOutcome.Success(adjustedA), AdjustmentOutcome.Success(adjustedA))
        val (first, _) = open(FakePlanning(PlanOutcome.Success(demoA, true)), adjustment, PlanStorageScope.DEMO)
        assertTrue(first.generate())
        advanceUntilIdle()
        first.select(1, "study-1")
        assertTrue(first.submitAdjustment())
        advanceUntilIdle()
        assertEquals(listOf("save", "adjust study-1", "save"), events)

        adjustment.gate = CompletableDeferred() // a second demo move never finishes
        first.selectFailedSession("study-2")
        assertTrue(first.submitAdjustment())
        advanceUntilIdle()

        val (second, _) = open(scope = PlanStorageScope.DEMO)
        assertEquals(PlanStorage.Ready, second.storage.value)
        assertEquals(movedA, second.plan(1).current.schedules)
        assertEquals(PlanRecovery.Ready, second.plan(1).recovery)
        assertTrue(second.plan(1).isDemo)
        assertTrue(open(scope = liveScope.replace("test", "other")).first.plans.value.isEmpty()) // another scope
    }
}
