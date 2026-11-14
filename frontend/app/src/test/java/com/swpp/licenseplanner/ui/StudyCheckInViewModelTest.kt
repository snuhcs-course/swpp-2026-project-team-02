package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentOutcome
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.BytesPlanStore
import com.swpp.licenseplanner.data.CalendarDestination
import com.swpp.licenseplanner.data.CalendarExportRepository
import com.swpp.licenseplanner.data.CalendarListOutcome
import com.swpp.licenseplanner.data.CalendarWriteOutcome
import com.swpp.licenseplanner.data.ExportNotStarted
import com.swpp.licenseplanner.data.LocalPlanStore
import com.swpp.licenseplanner.data.MappingResult
import com.swpp.licenseplanner.data.MemoryPlanBytes
import com.swpp.licenseplanner.data.PlanOutcome
import com.swpp.licenseplanner.data.PlanSnapshot
import com.swpp.licenseplanner.data.PlanSnapshotCodec
import com.swpp.licenseplanner.data.PlanStorageScope
import com.swpp.licenseplanner.data.PlanStoreLoad
import com.swpp.licenseplanner.data.PlanningRepository
import com.swpp.licenseplanner.data.QuestionOutcome
import com.swpp.licenseplanner.data.QuestionRepository
import com.swpp.licenseplanner.data.ReloadOutcome
import com.swpp.licenseplanner.data.RequestMapper
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CalendarEvent
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.StudyCheckIn
import com.swpp.licenseplanner.domain.StudyCheckInStatus
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
 * Reporting a study session from a restored collection: editing, validation, explicit saving and
 * the plan operations it must neither trigger nor overlap.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StudyCheckInViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = PlanStorageScope.of(demo = false, apiBaseUrl = "http://test/api/v1")
    private val disk = MemoryPlanBytes()

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

    private class FakeAdjustment(vararg replies: AdjustmentOutcome) : AdjustmentRepository {
        val queue = ArrayDeque(replies.toList())
        val inputs = mutableListOf<AdjustmentInput>()
        var gate: CompletableDeferred<Unit>? = null
        val reloads = ArrayDeque<ReloadOutcome>()
        var reloadCalls = 0
        var reloadGate: CompletableDeferred<Unit>? = null
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
            inputs += input
            gate?.await()
            return queue.removeFirst()
        }
        override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome {
            reloadCalls++
            reloadGate?.await()
            return reloads.removeFirst()
        }
    }

    private class CountingCalendar : CalendarExportRepository {
        var calls = 0
        override suspend fun calendars(): CalendarListOutcome {
            calls++
            return CalendarListOutcome.Unavailable
        }
        override suspend fun export(destination: CalendarDestination, events: List<CalendarEvent>): CalendarWriteOutcome {
            calls++
            return CalendarWriteOutcome.NotStarted(ExportNotStarted.PlanUnavailable)
        }
    }

    /** The real byte store with optional per-save gates (1-based save numbers). */
    private inner class GatedStore : LocalPlanStore {
        private val real = BytesPlanStore(disk, scope, dispatcher)
        val saveGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var saves = 0
            private set
        override suspend fun load(): PlanStoreLoad = real.load()
        override suspend fun save(snapshot: PlanSnapshot): Boolean {
            val number = ++saves
            saveGates[number]?.await()
            return real.save(snapshot)
        }
    }

    private val request = (RequestMapper.map(TestFixtures.completeState()) as MappingResult.Ready).request
    private val fileA = "license_planner/output/study_plan_a.csv"
    private val fileB = "license_planner/output/study_plan_b.csv"
    private val rowsA = listOf(
        ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/18/30", "함수"),
        ScheduleRow("study-2", "2026/10/06/18/00", "2026/10/06/18/30", "차트"),
        ScheduleRow("external-1", "2026/10/07/18/00", "2026/10/07/19/00", ""),
    )
    private val rowsB = listOf(
        ScheduleRow("study-1", "2026/10/08/18/00", "2026/10/08/18/30", "셀 참조"),
        ScheduleRow("external-1", "2026/10/09/18/00", "2026/10/09/19/00", ""),
    )
    private val planA = SessionPlan.register(1, request, StudyPlanResponse(rowsA, "csv-a", null, fileA), isDemo = false)
    private val planB = SessionPlan.register(2, request, StudyPlanResponse(rowsB, "csv-b", null, fileB), isDemo = false)
    private val movedA = rowsA.map { if (it.scheduleId == "study-1") it.copy(startDate = "2026/10/12/18/00", endDate = "2026/10/12/18/30") else it }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private class Opened(
        val vm: PlanFormViewModel,
        val store: GatedStore,
        val planning: FakePlanning,
        val adjustment: FakeAdjustment,
        val calendar: CountingCalendar,
    ) {
        val noRequests: Boolean
            get() = planning.requests.isEmpty() && adjustment.inputs.isEmpty() && adjustment.reloadCalls == 0 && calendar.calls == 0
    }

    /** Saves [plans] as the committed collection and starts a process over it. */
    private fun TestScope.open(
        vararg plans: SessionPlan,
        planning: FakePlanning = FakePlanning(),
        adjustment: FakeAdjustment = FakeAdjustment(),
    ): Opened {
        disk.committed = PlanSnapshotCodec.encode(PlanSnapshot(plans.toList()), scope).encodeToByteArray()
        val store = GatedStore()
        val calendar = CountingCalendar()
        val vm = PlanFormViewModel(
            FakeQuestions(), planning, adjustment, LocalDate.of(2026, 10, 5), store, calendarExportRepository = calendar,
        )
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, vm.storage.value)
        return Opened(vm, store, planning, adjustment, calendar)
    }

    private fun PlanFormViewModel.plan(ordinal: Int) = plans.value.single { it.ordinal == ordinal }

    private fun TestScope.restoredNow(): List<SessionPlan> {
        val store = BytesPlanStore(MemoryPlanBytes(disk.committed), scope, dispatcher)
        var result: PlanStoreLoad? = null
        launch { result = store.load() }
        advanceUntilIdle()
        return (result as PlanStoreLoad.Loaded).snapshot.plans
    }

    private fun TestScope.saveReport(vm: PlanFormViewModel, ordinal: Int, id: String, status: StudyCheckInStatus, minutes: String? = null) {
        assertTrue(vm.startCheckIn(ordinal, id))
        vm.chooseCheckInStatus(status)
        minutes?.let(vm::setCheckInMinutes)
        assertTrue(vm.saveCheckIn())
        advanceUntilIdle()
        assertEquals(CheckInSave.Saved, vm.checkIn.value?.save)
        vm.closeCheckIn()
    }

    @Test fun unreportedRowOpensWithoutAStatusAndDoneSuggestsTheScheduledMinutes() = runTest(dispatcher) {
        val opened = open(planA, planB)
        val vm = opened.vm
        assertTrue(vm.startCheckIn(2, "study-1"))
        val editor = vm.checkIn.value!!
        assertEquals(2 to "study-1", editor.planOrdinal to editor.scheduleId)
        assertEquals(30, editor.plannedMinutes)
        assertNull(editor.committed)
        assertNull(editor.status)
        assertEquals("", editor.minutesText)
        assertFalse(editor.minutesEnabled)

        vm.chooseCheckInStatus(StudyCheckInStatus.Done)
        assertEquals("30", vm.checkIn.value!!.minutesText)
        assertTrue(vm.checkIn.value!!.minutesEnabled)
        vm.setCheckInMinutes("20")
        assertTrue(vm.saveCheckIn())
        advanceUntilIdle()
        assertEquals(CheckInSave.Saved, vm.checkIn.value!!.save)
        assertEquals(StudyCheckIn(StudyCheckInStatus.Done, 20), vm.plan(2).checkInFor("study-1"))
        assertNull(vm.plan(1).checkInFor("study-1")) // same ID in another plan
        assertEquals(planA, vm.plan(1))
        assertEquals(rowsB, vm.plan(2).current.schedules)
        assertEquals(vm.plans.value, restoredNow())
        assertEquals(AdjustmentSelection(), vm.adjustment.value)
        assertTrue(opened.noRequests)
    }

    @Test fun correctingAReportPrefillsItAndReplacesOnlyThatReport() = runTest(dispatcher) {
        val other = StudyCheckIn(StudyCheckInStatus.Missed, 0)
        val opened = open(planA.copy(checkIns = mapOf("study-2" to other)), planB.copy(checkIns = mapOf("study-1" to other)))
        val vm = opened.vm
        saveReport(vm, 1, "study-1", StudyCheckInStatus.Done, "20")

        assertTrue(vm.startCheckIn(1, "study-1"))
        val editor = vm.checkIn.value!!
        assertEquals(StudyCheckIn(StudyCheckInStatus.Done, 20), editor.committed)
        assertEquals(StudyCheckInStatus.Done to "20", editor.status to editor.minutesText)
        vm.chooseCheckInStatus(StudyCheckInStatus.Partly)
        assertEquals("20", vm.checkIn.value!!.minutesText) // the saved minutes are kept
        vm.setCheckInMinutes("45") // more than the 30 scheduled minutes
        assertTrue(vm.saveCheckIn())
        advanceUntilIdle()

        val a = vm.plan(1)
        assertEquals(mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Partly, 45), "study-2" to other), a.checkIns)
        assertEquals(mapOf("study-1" to other), vm.plan(2).checkIns)
        assertEquals(rowsA, a.current.schedules)
        assertEquals(planA.original, a.original)
        assertEquals(vm.plans.value, restoredNow())
        assertEquals(2, opened.store.saves)
        assertTrue(opened.noRequests)
    }

    @Test fun missedSavesZeroMinutesWithMinuteEntryDisabled() = runTest(dispatcher) {
        val opened = open(planA)
        val vm = opened.vm
        assertTrue(vm.startCheckIn(1, "study-2"))
        vm.chooseCheckInStatus(StudyCheckInStatus.Done)
        assertEquals("30", vm.checkIn.value!!.minutesText)
        vm.chooseCheckInStatus(StudyCheckInStatus.Missed)
        assertFalse(vm.checkIn.value!!.minutesEnabled)
        vm.setCheckInMinutes("15") // ignored while missed
        assertEquals("30", vm.checkIn.value!!.minutesText)
        assertTrue(vm.saveCheckIn())
        advanceUntilIdle()
        assertEquals(StudyCheckIn(StudyCheckInStatus.Missed, 0), vm.plan(1).checkInFor("study-2"))

        // A saved missed report reopens with no minutes, so done suggests the schedule again
        // and partly needs explicit minutes.
        vm.closeCheckIn()
        assertTrue(vm.startCheckIn(1, "study-2"))
        assertEquals("", vm.checkIn.value!!.minutesText)
        vm.chooseCheckInStatus(StudyCheckInStatus.Partly)
        assertEquals("", vm.checkIn.value!!.minutesText)
        vm.chooseCheckInStatus(StudyCheckInStatus.Done)
        assertEquals("30", vm.checkIn.value!!.minutesText)
        assertTrue(opened.noRequests)
    }

    @Test fun cancellingWritesNothingAndKeepsTheSavedReport() = runTest(dispatcher) {
        val missed = StudyCheckIn(StudyCheckInStatus.Missed, 0)
        val opened = open(planA, planB.copy(checkIns = mapOf("study-1" to missed)))
        val vm = opened.vm
        val bytes = disk.committed
        assertTrue(vm.startCheckIn(2, "study-1"))
        vm.chooseCheckInStatus(StudyCheckInStatus.Done)
        vm.setCheckInMinutes("20")
        vm.closeCheckIn()
        advanceUntilIdle()
        assertNull(vm.checkIn.value)
        assertFalse(vm.saveCheckIn())
        assertEquals(missed, vm.plan(2).checkInFor("study-1"))
        assertEquals(0, opened.store.saves)
        assertArrayEquals(bytes, disk.committed)
        assertTrue(opened.noRequests)
    }

    @Test fun invalidInputShowsAnErrorAndKeepsTheSavedReport() = runTest(dispatcher) {
        val missed = StudyCheckIn(StudyCheckInStatus.Missed, 0)
        val opened = open(planA.copy(checkIns = mapOf("study-1" to missed)))
        val vm = opened.vm
        val bytes = disk.committed

        assertTrue(vm.startCheckIn(1, "study-2"))
        assertFalse(vm.saveCheckIn())
        assertEquals(CheckInInputError.StatusRequired, vm.checkIn.value!!.error)
        vm.closeCheckIn()

        assertTrue(vm.startCheckIn(1, "study-1"))
        for (status in listOf(StudyCheckInStatus.Done, StudyCheckInStatus.Partly)) {
            vm.chooseCheckInStatus(status)
            for (text in listOf("", "0", "-5", "1.5", "abc", " 20", "2147483648")) {
                vm.setCheckInMinutes(text)
                assertNull(vm.checkIn.value!!.error)
                assertFalse(vm.saveCheckIn())
                assertEquals(CheckInInputError.InvalidMinutes, vm.checkIn.value!!.error)
                assertEquals(CheckInSave.Editing, vm.checkIn.value!!.save)
            }
        }
        vm.setCheckInMinutes("2147483647") // the largest representable value is accepted
        assertNull(vm.checkIn.value!!.error)
        assertEquals(missed, vm.plan(1).checkInFor("study-1"))
        assertEquals(0, opened.store.saves)
        assertArrayEquals(bytes, disk.committed)
        assertTrue(vm.saveCheckIn())
        advanceUntilIdle()
        assertEquals(StudyCheckIn(StudyCheckInStatus.Partly, Int.MAX_VALUE), vm.plan(1).checkInFor("study-1"))
    }

    @Test fun externalAndUnknownRowsCannotBeReportedAndSelectionCreatesNoReport() = runTest(dispatcher) {
        val opened = open(planA, planB)
        val vm = opened.vm
        assertFalse(vm.startCheckIn(1, "external-1"))
        assertFalse(vm.startCheckIn(1, "study-9"))
        assertFalse(vm.startCheckIn(9, "study-1"))
        assertNull(vm.checkIn.value)

        assertTrue(vm.selectPlan(1))
        vm.selectFailedSession("study-1")
        assertTrue(vm.startCheckIn(1, "study-1"))
        assertEquals(AdjustmentSelection(1, "study-1"), vm.adjustment.value) // opening keeps the selection
        assertTrue(vm.plans.value.all { it.checkIns.isEmpty() })
        assertEquals(0, opened.store.saves)
        assertTrue(opened.noRequests)
    }

    @Test fun aSaveInProgressBlocksDuplicatesAndOtherPlanChanges() = runTest(dispatcher) {
        val opened = open(planA, planB, planning = FakePlanning(PlanOutcome.Success(planB.current, false)))
        val vm = opened.vm
        val gate = CompletableDeferred<Unit>()
        opened.store.saveGates[1] = gate
        assertTrue(vm.selectPlan(1))
        vm.selectFailedSession("study-2")
        assertTrue(vm.startCheckIn(1, "study-1"))
        vm.chooseCheckInStatus(StudyCheckInStatus.Done)
        assertTrue(vm.saveCheckIn())
        advanceUntilIdle()
        assertEquals(CheckInSave.Saving, vm.checkIn.value!!.save)
        assertEquals(PlanStorage.Saving, vm.storage.value)

        assertFalse(vm.saveCheckIn())
        assertFalse(vm.startCheckIn(2, "study-1"))
        assertFalse(vm.submitAdjustment())
        assertFalse(vm.generate())
        assertFalse(vm.startCalendarExport(2))
        vm.closeCheckIn() // refused while saving
        assertEquals(CheckInSave.Saving, vm.checkIn.value!!.save)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(CheckInSave.Saved, vm.checkIn.value!!.save)
        assertEquals(PlanStorage.Ready, vm.storage.value)
        assertEquals(1, opened.store.saves)
        assertEquals(StudyCheckIn(StudyCheckInStatus.Done, 30), restoredNow()[0].checkInFor("study-1"))
        assertTrue(opened.noRequests)
    }

    @Test fun runningPlanOperationsBlockOpeningAndSavingReports() = runTest(dispatcher) {
        val planning = FakePlanning(PlanOutcome.Success(planB.current, false)).apply { gate = CompletableDeferred() }
        val adjustment = FakeAdjustment(AdjustmentOutcome.Success(StudyPlanResponse(movedA, "csv", null, null))).apply {
            gate = CompletableDeferred()
        }
        val opened = open(planA, planB, planning = planning, adjustment = adjustment)
        val vm = opened.vm

        // An adjustment in flight: the report editor can neither open nor save.
        assertTrue(vm.startCheckIn(2, "study-1"))
        vm.chooseCheckInStatus(StudyCheckInStatus.Done)
        assertTrue(vm.selectPlan(1))
        vm.selectFailedSession("study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        assertFalse(vm.startCheckIn(1, "study-2"))
        assertFalse(vm.saveCheckIn())
        adjustment.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.saveCheckIn())
        advanceUntilIdle()
        vm.closeCheckIn()

        // An open calendar export.
        assertTrue(vm.startCalendarExport(2))
        assertFalse(vm.startCheckIn(2, "study-1"))
        vm.closeCalendarExport()

        // A generation in flight.
        vm.selectCertification(CERTIFICATION_ID)
        vm.setExamDate(LocalDate.of(2026, 11, 16))
        vm.setLevel(CurrentLevel.INTERMEDIATE)
        vm.loadQuestions()
        advanceUntilIdle()
        vm.form.value.questions.forEach { vm.answer(it.id, it.correctAnswer) }
        vm.setDayEnabled(Weekday.MONDAY, true)
        assertTrue(vm.generate())
        advanceUntilIdle()
        assertFalse(vm.startCheckIn(2, "study-1"))
        planning.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.startCheckIn(2, "study-1"))
        assertEquals(StudyCheckIn(StudyCheckInStatus.Done, 30), vm.plan(2).checkInFor("study-1"))
        assertEquals(movedA, vm.plan(1).current.schedules)
        assertEquals(0, opened.calendar.calls)
    }

    @Test fun aPlanAwaitingReloadCanBeReportedAndKeepsItsGate() = runTest(dispatcher) {
        val gated = planA.copy(recovery = PlanRecovery.ReloadRequired, recoveryScheduleId = "study-2")
        val missing = planB.copy(recovery = PlanRecovery.Missing)
        val opened = open(gated, missing)
        val vm = opened.vm
        assertTrue(vm.selectPlan(1))
        saveReport(vm, 1, "study-1", StudyCheckInStatus.Partly, "10")
        saveReport(vm, 2, "study-1", StudyCheckInStatus.Missed)

        val restored = restoredNow()
        assertEquals(PlanRecovery.ReloadRequired to "study-2", restored[0].recovery to restored[0].recoveryScheduleId)
        assertEquals(StudyCheckIn(StudyCheckInStatus.Partly, 10), restored[0].checkInFor("study-1"))
        assertEquals(PlanRecovery.Missing, restored[1].recovery)
        assertEquals(StudyCheckIn(StudyCheckInStatus.Missed, 0), restored[1].checkInFor("study-1"))
        assertEquals(AdjustmentSelection(1, "study-2"), vm.adjustment.value)
        assertTrue(opened.noRequests)
    }

    @Test fun unsavedReportsAreDistinguishedFromSavedOnes() {
        val report = StudyCheckIn(StudyCheckInStatus.Done, 20)
        val shown = listOf(planA.copy(checkIns = mapOf("study-1" to report)), planB)
        assertTrue(isCheckInUnsaved(shown, listOf(planA, planB), 1, "study-1"))
        assertFalse(isCheckInUnsaved(shown, shown, 1, "study-1"))
        assertFalse(isCheckInUnsaved(shown, listOf(planA, planB), 2, "study-1"))
        assertTrue(isCheckInUnsaved(shown, emptyList(), 1, "study-1"))
    }

    @Test fun aRowRemovedWhileEditingIsNotSaved() = runTest(dispatcher) {
        val gated = planA.copy(recovery = PlanRecovery.ReloadRequired, recoveryScheduleId = "study-2")
        val adjustment = FakeAdjustment().apply { reloads += ReloadOutcome.Loaded(rowsA.filter { it.scheduleId != "study-1" }) }
        val opened = open(gated, adjustment = adjustment)
        val vm = opened.vm
        assertTrue(vm.startCheckIn(1, "study-1"))
        vm.chooseCheckInStatus(StudyCheckInStatus.Done)
        assertTrue(vm.selectPlan(1))
        assertTrue(vm.reloadPlan())
        advanceUntilIdle()
        assertFalse(vm.saveCheckIn())
        assertTrue(vm.plan(1).checkIns.isEmpty())
        assertEquals(1, adjustment.reloadCalls)
        assertTrue(adjustment.inputs.isEmpty())
    }
}
