package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentOutcome
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.BytesPlanStore
import com.swpp.licenseplanner.data.CalendarDestination
import com.swpp.licenseplanner.data.CalendarExportRepository
import com.swpp.licenseplanner.data.CalendarListOutcome
import com.swpp.licenseplanner.data.CalendarWriteOutcome
import com.swpp.licenseplanner.data.ExportNotStarted
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
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CalendarEvent
import com.swpp.licenseplanner.domain.CalendarExport
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.Weekday
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

/** The retained calendar export flow, with delayed and failing fakes; no device calendar. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarExportViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val disk = MemoryPlanBytes()
    private val scope = PlanStorageScope.of(demo = false, apiBaseUrl = "http://test/api/v1")

    private class FakeQuestions : QuestionRepository {
        override suspend fun load(certificationId: String) = QuestionOutcome.Loaded(TestFixtures.publicQuestions())
    }

    private class FakePlanning(vararg replies: PlanOutcome) : PlanningRepository {
        val queue = ArrayDeque(replies.toList())
        var requests = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun generate(request: StudyPlanRequest): PlanOutcome {
            requests++
            gate?.await()
            return queue.removeFirst()
        }
    }

    private class FakeAdjustment(vararg replies: AdjustmentOutcome) : AdjustmentRepository {
        val queue = ArrayDeque(replies.toList())
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
            calls++
            gate?.await()
            return queue.removeFirst()
        }
        var reloadGate: CompletableDeferred<Unit>? = null
        override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome {
            calls++
            reloadGate?.await()
            return ReloadOutcome.Failure(ApiError.Connection)
        }
    }

    /** Records every call; [exportGate] delays the write (the delayed fake). */
    private class FakeCalendar : CalendarExportRepository {
        var list: CalendarListOutcome = CalendarListOutcome.Loaded(listOf(work, study))
        var listGate: CompletableDeferred<Unit>? = null
        var listCalls = 0
        var result: CalendarWriteOutcome? = null
        var exportGate: CompletableDeferred<Unit>? = null
        val exports = mutableListOf<Pair<CalendarDestination, List<CalendarEvent>>>()

        override suspend fun calendars(): CalendarListOutcome {
            listCalls++
            listGate?.await()
            return list
        }
        override suspend fun export(destination: CalendarDestination, events: List<CalendarEvent>): CalendarWriteOutcome {
            exports += destination to events
            exportGate?.await()
            return result ?: CalendarWriteOutcome.Complete(events.size)
        }
    }

    /** The byte store with an optional gate on the next save. */
    private inner class GatedStore : LocalPlanStore {
        private val real = BytesPlanStore(disk, scope, dispatcher)
        var saveGate: CompletableDeferred<Unit>? = null
        override suspend fun load(): PlanStoreLoad = real.load()
        override suspend fun save(snapshot: PlanSnapshot): Boolean {
            saveGate?.await()
            return real.save(snapshot)
        }
    }

    companion object {
        val work = CalendarDestination(1, "업무")
        val study = CalendarDestination(2, "공부")
    }

    private val fileA = "license_planner/output/study_plan_a.csv"
    private val fileB = "license_planner/output/study_plan_b.csv"
    private val rowsA = listOf(
        ScheduleRow("study-1", "2026/10/19/18/00", "2026/10/19/19/00", "함수"),
        ScheduleRow("external-1", "2026/10/20/18/00", "2026/10/20/19/00", ""),
        ScheduleRow("study-2", "2026/10/26/18/00", "2026/10/26/19/20", "차트"),
    )
    private val rowsB = listOf(
        ScheduleRow("study-1", "2026/10/21/18/00", "2026/10/21/18/25", "셀 참조"),
        ScheduleRow("study-2", "2026/11/04/18/00", "2026/11/04/19/00", "데이터 관리"),
    )
    private val planA = StudyPlanResponse(rowsA, "csv-a", null, fileA)
    private val planB = StudyPlanResponse(rowsB, "csv-b", null, fileB)
    private val movedB = rowsB.map { if (it.scheduleId == "study-1") it.copy(startDate = "2026/10/28/18/00", endDate = "2026/10/28/18/25") else it }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** A restored view model over [disk] with a complete Monday form. */
    private fun TestScope.open(
        planning: PlanningRepository = FakePlanning(),
        adjustment: AdjustmentRepository = FakeAdjustment(),
        calendar: CalendarExportRepository = FakeCalendar(),
        store: LocalPlanStore = GatedStore(),
    ) = PlanFormViewModel(FakeQuestions(), planning, adjustment, LocalDate.of(2026, 10, 5), store, calendar,
        calendarReadRepository = ClearCalendarForTest,
    ).apply {
        selectCertification(CERTIFICATION_ID)
        setExamDate(LocalDate.of(2026, 11, 16))
        setLevel(CurrentLevel.INTERMEDIATE)
        loadQuestions()
        advanceUntilIdle()
        form.value.questions.forEach { answer(it.id, it.correctAnswer) }
        setDayEnabled(Weekday.MONDAY, true)
    }

    private fun TestScope.generate(vm: PlanFormViewModel, times: Int = 1) = repeat(times) {
        assertTrue(vm.generateAfterCalendarCheck())
        advanceUntilIdle()
    }

    private val PlanFormViewModel.phase get() = calendarExport.value?.phase

    /** Starts an export of [ordinal], grants access and chooses [destination]; stops at confirmation. */
    private fun TestScope.reachConfirmation(vm: PlanFormViewModel, ordinal: Int, destination: CalendarDestination = study) {
        assertTrue(vm.startCalendarExport(ordinal))
        assertTrue(vm.calendarPermissionPrompted())
        vm.onCalendarPermission(true)
        advanceUntilIdle()
        vm.chooseCalendar(destination)
        vm.reviewCalendarExport()
        advanceUntilIdle()
        assertEquals(CalendarExportPhase.Confirming(listOf(work, study), destination), vm.phase)
    }

    // --- Source capture ---

    @Test fun reopenedPlanExportsItsLatestRowsAcrossAllWeeksNotAnotherPlansSharedIds() = runTest(dispatcher) {
        val calendar = FakeCalendar()
        val adjustment = FakeAdjustment(AdjustmentOutcome.Success(planB.copy(schedules = movedB)))
        val vm = open(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), adjustment, calendar)
        generate(vm, 2)
        assertTrue(vm.selectPlan(2))
        vm.selectFailedSession("study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(movedB, vm.plans.value.single { it.ordinal == 2 }.current.schedules)

        reachConfirmation(vm, 2)
        assertTrue(vm.confirmCalendarExport())
        advanceUntilIdle()

        val (destination, events) = calendar.exports.single()
        assertEquals(study, destination)
        assertEquals(CalendarExport.events(movedB, 2, isDemo = false), events)
        assertEquals(listOf("셀 참조", "데이터 관리"), events.map { it.title })
        assertEquals(CalendarExportPhase.Finished(study, CalendarWriteOutcome.Complete(2)), vm.phase)
        // A and its rows are untouched.
        assertEquals(rowsA, vm.plans.value.single { it.ordinal == 1 }.current.schedules)
    }

    @Test fun livePlanWithoutServerIdentityCanStillBeExported() = runTest(dispatcher) {
        val calendar = FakeCalendar()
        val vm = open(FakePlanning(PlanOutcome.Success(planA.copy(scheduleCsvFile = null), false)), calendar = calendar)
        generate(vm)
        assertFalse(vm.plans.value.single().canAdjust)
        reachConfirmation(vm, 1)
        assertTrue(vm.confirmCalendarExport())
        advanceUntilIdle()
        assertEquals(2, calendar.exports.single().second.size)
    }

    @Test fun demoPlanEventsCarryTheDemoLabel() = runTest(dispatcher) {
        val calendar = FakeCalendar()
        val vm = open(FakePlanning(PlanOutcome.Success(planA.copy(scheduleCsvFile = null), true)), calendar = calendar)
        generate(vm)
        reachConfirmation(vm, 1)
        assertTrue(vm.calendarExport.value!!.source.isDemo)
        assertTrue(vm.confirmCalendarExport())
        advanceUntilIdle()
        assertTrue(calendar.exports.single().second.all { it.description.contains("로컬 시연 계산") })
    }

    // --- Gates ---

    @Test fun plansThatCannotBeExportedRefuseToStart() = runTest(dispatcher) {
        val externalOnly = StudyPlanResponse(listOf(rowsA[1]), "", null, fileA)
        val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(ApiError.Connection))
        val store = GatedStore()
        val planning = FakePlanning(PlanOutcome.Success(externalOnly, false), PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false))
        val vm = open(planning, adjustment, store = store)
        generate(vm, 2)
        assertFalse(vm.startCalendarExport(1)) // no study rows
        assertFalse(vm.startCalendarExport(9)) // no such plan

        // Saving a new plan: storage is not ready.
        store.saveGate = CompletableDeferred()
        assertTrue(vm.generateAfterCalendarCheck())
        advanceUntilIdle()
        assertEquals(PlanStorage.Saving, vm.storage.value)
        assertFalse(vm.startCalendarExport(2))
        store.saveGate!!.complete(Unit)
        advanceUntilIdle()

        // Generating and adjusting are in progress.
        planning.gate = CompletableDeferred()
        planning.queue.addLast(PlanOutcome.Success(planB, false))
        assertTrue(vm.generateAfterCalendarCheck())
        assertFalse(vm.startCalendarExport(2))
        planning.gate!!.complete(Unit)
        advanceUntilIdle()
        adjustment.gate = CompletableDeferred()
        assertTrue(vm.selectPlan(2))
        vm.selectFailedSession("study-1")
        assertTrue(vm.submitAdjustment())
        assertFalse(vm.startCalendarExport(3))
        adjustment.gate!!.complete(Unit)
        advanceUntilIdle()

        // The lost response leaves plan 2 needing a reload.
        assertEquals(PlanRecovery.ReloadRequired, vm.plans.value.single { it.ordinal == 2 }.recovery)
        assertFalse(vm.startCalendarExport(2))
        assertTrue(vm.startCalendarExport(3))
        assertEquals(ExportBlock.Busy, exportBlock(vm.plans.value.single { it.ordinal == 3 }, vm.storage.value, operationBusy = true))
    }

    @Test fun invalidStudyRowsEndTheFlowWithoutAskingOrWriting() = runTest(dispatcher) {
        val calendar = FakeCalendar()
        val bad = planA.copy(schedules = rowsA + ScheduleRow("study-1", "2026/10/27/18/00", "2026/10/27/19/00", "중복"))
        val vm = open(FakePlanning(PlanOutcome.Success(bad, false)), calendar = calendar)
        generate(vm)
        assertTrue(vm.startCalendarExport(1))
        assertEquals(CalendarExportPhase.InvalidRows, vm.phase)
        assertFalse(vm.calendarPermissionPrompted())
        assertFalse(vm.confirmCalendarExport())
        assertEquals(0, calendar.listCalls)
        assertTrue(calendar.exports.isEmpty())
        vm.closeCalendarExport()
        assertNull(vm.calendarExport.value)
    }

    // --- Permission, choice and cancellation ---

    @Test fun deniedAccessAndCancellationWriteNothing() = runTest(dispatcher) {
        val calendar = FakeCalendar()
        val vm = open(FakePlanning(PlanOutcome.Success(planA, false)), calendar = calendar)
        generate(vm)

        assertTrue(vm.startCalendarExport(1))
        assertTrue(vm.calendarPermissionPrompted())
        assertFalse(vm.calendarPermissionPrompted()) // the prompt is shown once per request
        vm.onCalendarPermission(false)
        assertEquals(CalendarExportPhase.PermissionDenied, vm.phase)
        vm.onCalendarPermission(true) // a late answer does not reopen the flow
        assertEquals(CalendarExportPhase.PermissionDenied, vm.phase)
        vm.closeCalendarExport()

        // Cancel while choosing, then while confirming.
        assertTrue(vm.startCalendarExport(1))
        vm.onCalendarPermission(true)
        advanceUntilIdle()
        assertEquals(CalendarExportPhase.Choosing(listOf(work, study)), vm.phase)
        vm.reviewCalendarExport() // nothing chosen yet
        assertEquals(CalendarExportPhase.Choosing(listOf(work, study)), vm.phase)
        vm.chooseCalendar(CalendarDestination(99, "목록에 없음"))
        assertEquals(CalendarExportPhase.Choosing(listOf(work, study)), vm.phase)
        vm.closeCalendarExport()
        reachConfirmation(vm, 1, work)
        vm.chooseAnotherCalendar()
        assertEquals(CalendarExportPhase.Choosing(listOf(work, study), work), vm.phase)
        vm.reviewCalendarExport()
        vm.closeCalendarExport()

        assertNull(vm.calendarExport.value)
        assertTrue(calendar.exports.isEmpty())
        assertEquals(rowsA, vm.plans.value.single().current.schedules)
    }

    @Test fun calendarListProblemsEndWithoutWriting() = runTest(dispatcher) {
        val calendar = FakeCalendar()
        val vm = open(FakePlanning(PlanOutcome.Success(planA, false)), calendar = calendar)
        generate(vm)
        val expected = mapOf(
            CalendarListOutcome.Loaded(emptyList()) to CalendarExportPhase.NoCalendars,
            CalendarListOutcome.Unavailable to CalendarExportPhase.CalendarsUnavailable,
            CalendarListOutcome.PermissionMissing to CalendarExportPhase.PermissionDenied,
        )
        for ((outcome, phase) in expected) {
            calendar.list = outcome
            assertTrue(vm.startCalendarExport(1))
            vm.onCalendarPermission(true)
            advanceUntilIdle()
            assertEquals(phase, vm.phase)
            vm.closeCalendarExport()
        }
        assertTrue(calendar.exports.isEmpty())
    }

    @Test fun aLateCalendarListNeverLandsInANewerFlow() = runTest(dispatcher) {
        val calendar = FakeCalendar().apply { listGate = CompletableDeferred() }
        val vm = open(FakePlanning(PlanOutcome.Success(planA, false)), calendar = calendar)
        generate(vm)
        assertTrue(vm.startCalendarExport(1))
        vm.onCalendarPermission(true)
        vm.closeCalendarExport()
        assertTrue(vm.startCalendarExport(1))
        calendar.listGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(CalendarExportPhase.RequestingPermission, vm.phase)
    }

    // --- Observed availability ---

    /** Collects the observed export availability like a screen; [latest] is the last value it received. */
    private class Observed(private val scope: TestScope, vm: PlanFormViewModel) {
        private val seen = mutableListOf<Map<Int, ExportBlock?>>()
        init {
            scope.backgroundScope.launch { vm.exportBlocks.collect { seen += it } }
        }
        /** Runs pending collection first, since idling the test does not wait for background work. */
        val latest: Map<Int, ExportBlock?> get() = scope.runCurrent().let { seen.last() }
    }

    @Test fun observedAvailabilityFollowsOperationsStorageAndEachPlan() = runTest(dispatcher) {
        val externalOnly = StudyPlanResponse(listOf(rowsA[1]), "", null, "license_planner/output/study_plan_c.csv")
        val planD = planB.copy(scheduleCsvFile = "license_planner/output/study_plan_d.csv")
        val planning = FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false),
            PlanOutcome.Success(externalOnly, false))
        val adjustment = FakeAdjustment(AdjustmentOutcome.Failure(ApiError.Connection))
        val store = GatedStore()
        val vm = open(planning, adjustment, store = store)
        generate(vm, 3)
        val seen = Observed(this, vm)
        val busy = ExportBlock.Busy
        assertEquals(mapOf(1 to null, 2 to null, 3 to ExportBlock.NoStudy), seen.latest)
        assertFalse(vm.startCalendarExport(3))

        // Generating, then saving the new plan.
        planning.gate = CompletableDeferred()
        planning.queue.addLast(PlanOutcome.Success(planD, false))
        assertTrue(vm.generateAfterCalendarCheck())
        assertEquals(mapOf(1 to busy, 2 to busy, 3 to ExportBlock.NoStudy), seen.latest)
        assertFalse(vm.startCalendarExport(1))
        store.saveGate = CompletableDeferred()
        planning.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(PlanStorage.Saving, vm.storage.value)
        val storage = ExportBlock.Storage
        assertEquals(mapOf(1 to storage, 2 to storage, 3 to ExportBlock.NoStudy, 4 to storage), seen.latest)
        assertFalse(vm.startCalendarExport(4))
        store.saveGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(mapOf(1 to null, 2 to null, 3 to ExportBlock.NoStudy, 4 to null), seen.latest)

        // Adjusting plan 2 holds every plan; its lost response leaves only plan 2 needing a reload.
        adjustment.gate = CompletableDeferred()
        assertTrue(vm.selectPlan(2))
        vm.selectFailedSession("study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertEquals(busy, seen.latest[1])
        assertEquals(busy, seen.latest[4])
        assertFalse(vm.startCalendarExport(1))
        adjustment.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(mapOf(1 to null, 2 to ExportBlock.Recovery, 3 to ExportBlock.NoStudy, 4 to null), seen.latest)
        assertFalse(vm.startCalendarExport(2))

        // Reloading plan 2.
        adjustment.reloadGate = CompletableDeferred()
        assertTrue(vm.reloadPlan())
        assertEquals(mapOf(1 to busy, 2 to ExportBlock.Recovery, 3 to ExportBlock.NoStudy, 4 to busy), seen.latest)
        assertFalse(vm.startCalendarExport(1))
        adjustment.reloadGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(mapOf(1 to null, 2 to ExportBlock.Recovery, 3 to ExportBlock.NoStudy, 4 to null), seen.latest)

        // An open export holds the others; a second immediate start opens nothing new.
        assertTrue(vm.startCalendarExport(1))
        val flow = vm.calendarExport.value
        assertFalse(vm.startCalendarExport(1))
        assertFalse(vm.startCalendarExport(4))
        assertEquals(flow, vm.calendarExport.value)
        assertEquals(mapOf(1 to busy, 2 to ExportBlock.Recovery, 3 to ExportBlock.NoStudy, 4 to busy), seen.latest)
        vm.closeCalendarExport()
        assertEquals(null, seen.latest[1])
    }

    @Test fun confirmationIsNotBlockedByItsOwnOpenFlow() = runTest(dispatcher) {
        val calendar = FakeCalendar().apply { exportGate = CompletableDeferred() }
        val vm = open(FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false)), calendar = calendar)
        generate(vm, 2)
        val seen = Observed(this, vm)
        reachConfirmation(vm, 1)
        // Both screens show the action unavailable while the dialog is open...
        assertEquals(mapOf(1 to ExportBlock.Busy, 2 to ExportBlock.Busy), seen.latest)
        assertFalse(vm.startCalendarExport(2))
        // ...yet its own confirmation is valid and writes once.
        assertTrue(vm.confirmCalendarExport())
        assertFalse(vm.confirmCalendarExport())
        advanceUntilIdle()
        assertEquals(mapOf(1 to ExportBlock.Busy, 2 to ExportBlock.Busy), seen.latest)
        calendar.exportGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(CalendarExportPhase.Finished(study, CalendarWriteOutcome.Complete(2)), vm.phase)
        assertEquals(1, calendar.exports.size)
        vm.closeCalendarExport()
        assertEquals(mapOf(1 to null, 2 to null), seen.latest)
    }

    // --- Writing ---

    @Test fun oneConfirmedWriteHoldsThePlanUntilItEnds() = runTest(dispatcher) {
        val calendar = FakeCalendar().apply { exportGate = CompletableDeferred() }
        val planning = FakePlanning(PlanOutcome.Success(planA, false), PlanOutcome.Success(planB, false))
        val adjustment = FakeAdjustment()
        val vm = open(planning, adjustment, calendar)
        generate(vm, 2)
        assertTrue(vm.selectPlan(1))
        vm.selectFailedSession("study-1")
        reachConfirmation(vm, 1)

        assertTrue(vm.confirmCalendarExport())
        assertFalse(vm.confirmCalendarExport()) // double tap
        assertEquals(CalendarExportPhase.Checking, vm.phase)
        advanceUntilIdle()
        assertEquals(CalendarExportPhase.Writing(study), vm.phase)
        assertEquals(1, calendar.exports.size)

        // Competing operations and closing are refused while writing.
        assertFalse(vm.generateAfterCalendarCheck())
        assertFalse(vm.selectPlan(2))
        assertFalse(vm.submitAdjustment())
        assertFalse(vm.reloadPlan())
        assertFalse(vm.startCalendarExport(2))
        vm.selectFailedSession("study-2")
        vm.closeCalendarExport()
        assertEquals(CalendarExportPhase.Writing(study), vm.phase)
        assertEquals("study-1", vm.adjustment.value.failedScheduleId)
        assertEquals(2, planning.requests)
        assertEquals(0, adjustment.calls)

        calendar.exportGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(CalendarExportPhase.Finished(study, CalendarWriteOutcome.Complete(2)), vm.phase)
        assertEquals(1, calendar.exports.size)
        // The result stays until dismissed; dismissing releases the plan.
        assertFalse(vm.selectPlan(2))
        vm.closeCalendarExport()
        assertTrue(vm.selectPlan(2))
    }

    @Test fun partialAndUncertainOutcomesAreReportedAsReturned() = runTest(dispatcher) {
        val calendar = FakeCalendar()
        val vm = open(FakePlanning(PlanOutcome.Success(planA, false)), calendar = calendar)
        generate(vm)
        val outcomes = listOf(
            CalendarWriteOutcome.Stopped(added = 1, uncertain = false),
            CalendarWriteOutcome.Stopped(added = 1, uncertain = true),
            CalendarWriteOutcome.NotStarted(ExportNotStarted.DestinationUnavailable),
        )
        for (outcome in outcomes) {
            calendar.result = outcome
            reachConfirmation(vm, 1)
            assertTrue(vm.confirmCalendarExport())
            advanceUntilIdle()
            assertEquals(CalendarExportPhase.Finished(study, outcome), vm.phase)
            vm.closeCalendarExport()
        }
        // Each export was a deliberate new flow with its own confirmation; nothing was retried.
        assertEquals(3, calendar.exports.size)
        assertEquals(rowsA, vm.plans.value.single().current.schedules)
    }

    @Test fun anotherExportAfterSuccessNeedsANewConfirmation() = runTest(dispatcher) {
        val calendar = FakeCalendar()
        val vm = open(FakePlanning(PlanOutcome.Success(planA, false)), calendar = calendar)
        generate(vm)
        reachConfirmation(vm, 1)
        assertTrue(vm.confirmCalendarExport())
        advanceUntilIdle()
        vm.closeCalendarExport()

        assertTrue(vm.startCalendarExport(1))
        assertEquals(CalendarExportPhase.RequestingPermission, vm.phase)
        assertFalse(vm.confirmCalendarExport())
        vm.onCalendarPermission(true)
        advanceUntilIdle()
        assertFalse(vm.confirmCalendarExport())
        assertEquals(1, calendar.exports.size)
    }

    // --- Restart ---

    @Test fun restartRestoresPlansWithoutResumingAnExport() = runTest(dispatcher) {
        val firstCalendar = FakeCalendar().apply { exportGate = CompletableDeferred() }
        val first = open(FakePlanning(PlanOutcome.Success(planA, false)), calendar = firstCalendar)
        generate(first)
        reachConfirmation(first, 1)
        assertTrue(first.confirmCalendarExport())
        advanceUntilIdle()
        val bytes = disk.committed!!.copyOf()
        val writes = disk.writes

        // A new process over the same bytes while the first export is still writing.
        val planning = FakePlanning()
        val adjustment = FakeAdjustment()
        val calendar = FakeCalendar()
        val second = open(planning, adjustment, calendar)
        advanceUntilIdle()
        assertEquals(first.plans.value, second.plans.value)
        assertNull(second.calendarExport.value)
        assertEquals(0, calendar.listCalls)
        assertTrue(calendar.exports.isEmpty())
        assertEquals(0, planning.requests)
        assertEquals(0, adjustment.calls)
        // Exporting never wrote the saved plans.
        firstCalendar.exportGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, firstCalendar.exports.size)
        assertEquals(writes, disk.writes)
        assertTrue(bytes.contentEquals(disk.committed))
    }
}
