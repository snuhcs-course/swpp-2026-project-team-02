package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.BytesPlanStore
import com.swpp.licenseplanner.data.CalendarBusySnapshot
import com.swpp.licenseplanner.data.CalendarReadOutcome
import com.swpp.licenseplanner.data.CalendarReadRepository
import com.swpp.licenseplanner.data.CalendarReadWindow
import com.swpp.licenseplanner.data.DemoPlanningRepository
import com.swpp.licenseplanner.data.MemoryPlanBytes
import com.swpp.licenseplanner.data.PlanOutcome
import com.swpp.licenseplanner.data.PlanStorageScope
import com.swpp.licenseplanner.data.PlanningRepository
import com.swpp.licenseplanner.data.QuestionOutcome
import com.swpp.licenseplanner.data.QuestionRepository
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import com.swpp.licenseplanner.domain.Weekday
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
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

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarReadViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class Questions : QuestionRepository {
        override suspend fun load(certificationId: String) = QuestionOutcome.Loaded(TestFixtures.publicQuestions())
    }

    private class Planning(private val response: StudyPlanResponse = StudyPlanResponse(emptyList())) : PlanningRepository {
        val requests = mutableListOf<StudyPlanRequest>()
        override suspend fun generate(request: StudyPlanRequest): PlanOutcome {
            requests += request
            return PlanOutcome.Success(response, false)
        }
    }

    private class Adjustment : AdjustmentRepository {
        override suspend fun adjust(input: AdjustmentInput) = error("not used")
        override suspend fun reload(scheduleCsvFile: String?) = error("not used")
    }

    private class Calendar(var outcome: CalendarReadOutcome) : CalendarReadRepository {
        val windows = mutableListOf<CalendarReadWindow>()
        /** Holds the read in progress until completed (the delayed fake). */
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun read(window: CalendarReadWindow): CalendarReadOutcome {
            windows += window
            gate?.await()
            return outcome
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.open(calendar: Calendar, planning: Planning = Planning()): Pair<PlanFormViewModel, Planning> {
        val vm = PlanFormViewModel(
            Questions(),
            planning,
            Adjustment(),
            today = LocalDate.of(2026, 11, 1),
            planStore = BytesPlanStore(
                MemoryPlanBytes(),
                PlanStorageScope.of(demo = false, apiBaseUrl = "http://test/api/v1"),
                dispatcher,
            ),
            calendarReadRepository = calendar,
        )
        vm.selectCertification(CERTIFICATION_ID)
        vm.setExamDate(LocalDate.of(2026, 12, 1))
        vm.setLevel(CurrentLevel.INTERMEDIATE)
        vm.loadQuestions()
        advanceUntilIdle()
        vm.form.value.questions.forEach { vm.answer(it.id, it.correctAnswer) }
        vm.setDayEnabled(Weekday.MONDAY, true)
        return vm to planning
    }

    private fun TestScope.generateReviewed(vm: PlanFormViewModel): Boolean {
        if (!vm.generate()) return false
        vm.onConflictPermission(true)
        advanceUntilIdle()
        when (vm.calendarConflict.value?.phase) {
            ConflictPhase.Review -> vm.continueCalendarConflict()
            ConflictPhase.Unchecked -> vm.continueCalendarConflict(true)
            else -> Unit
        }
        advanceUntilIdle()
        return true
    }

    @Test fun allowedImportIsIncludedInTheNextGenerationRequest() = runTest(dispatcher) {
        val busy = ScheduleRow("17", "2026/11/02/18/00", "2026/11/02/19/00", "외부 일정")
        val calendar = Calendar(CalendarReadOutcome.Loaded(CalendarBusySnapshot(listOf(busy), 1, 0)))
        val (vm, planning) = open(calendar)

        assertTrue(vm.startCalendarRead())
        assertTrue(vm.calendarReadPermissionPrompted())
        vm.onCalendarReadPermission(true)
        advanceUntilIdle()

        assertEquals(CalendarReadWindow(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1)), calendar.windows.single())
        assertEquals(CalendarReadState.Loaded(1, 1, 0), vm.calendarRead.value)
        assertEquals(listOf(busy), vm.form.value.busySchedules)
        assertTrue(generateReviewed(vm))
        advanceUntilIdle()
        assertEquals(listOf(busy), planning.requests.single().busySchedules)
    }

    @Test fun denialAndEmptyCalendarDoNotBlockGeneration() = runTest(dispatcher) {
        val calendar = Calendar(CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0)))
        val (vm, planning) = open(calendar)

        assertTrue(vm.startCalendarRead())
        assertTrue(vm.calendarReadPermissionPrompted())
        vm.onCalendarReadPermission(false)
        assertEquals(CalendarReadState.PermissionDenied, vm.calendarRead.value)
        assertTrue(generateReviewed(vm))
        advanceUntilIdle()
        assertTrue(planning.requests.single().busySchedules.isEmpty())

        val (emptyVm, _) = open(calendar)
        assertTrue(emptyVm.startCalendarRead())
        emptyVm.onCalendarReadPermission(true)
        advanceUntilIdle()
        assertEquals(CalendarReadState.Empty, emptyVm.calendarRead.value)
        assertTrue(generateReviewed(emptyVm))
    }

    @Test fun providerFailureIsRetryableAndDateChangeInvalidatesImportedRows() = runTest(dispatcher) {
        val busy = ScheduleRow("8", "2026/11/03/09/00", "2026/11/03/10/00", "외부 일정")
        val calendar = Calendar(CalendarReadOutcome.Unavailable)
        val (vm, _) = open(calendar)
        assertTrue(vm.startCalendarRead())
        vm.onCalendarReadPermission(true)
        advanceUntilIdle()
        assertEquals(CalendarReadState.Unavailable, vm.calendarRead.value)
        assertTrue(vm.form.value.busySchedules.isEmpty())

        calendar.outcome = CalendarReadOutcome.Loaded(CalendarBusySnapshot(listOf(busy), 0, 0))
        assertTrue(vm.startCalendarRead())
        vm.onCalendarReadPermission(true)
        advanceUntilIdle()
        assertEquals(listOf(busy), vm.form.value.busySchedules)

        vm.setExamDate(LocalDate.of(2026, 12, 8))
        assertEquals(CalendarReadState.Idle, vm.calendarRead.value)
        assertTrue(vm.form.value.busySchedules.isEmpty())
        assertFalse(vm.calendarRead.value is CalendarReadState.Loaded)
    }

    /** The demo flavor's real planner, recording each request it receives. */
    private class RecordingDemoPlanning : PlanningRepository {
        private val demo = DemoPlanningRepository()
        val requests = mutableListOf<StudyPlanRequest>()
        override suspend fun generate(request: StudyPlanRequest): PlanOutcome {
            requests += request
            return demo.generate(request)
        }
    }

    @Test fun laterExamDateAfterImportedShortfallChecksFreshBusyRows() = runTest(dispatcher) {
        val busy = listOf(
            ScheduleRow("17", "2026/11/09/18/00", "2026/11/09/19/00", "외부 일정"),
            ScheduleRow("23", "2026/11/11/18/00", "2026/11/11/19/00", "외부 일정"),
        )
        val calendar = Calendar(CalendarReadOutcome.Loaded(CalendarBusySnapshot(busy, 0, 0)))
        val planning = RecordingDemoPlanning()
        val vm = PlanFormViewModel(
            Questions(),
            planning,
            Adjustment(),
            today = LocalDate.of(2026, 11, 9),
            planStore = BytesPlanStore(
                MemoryPlanBytes(),
                PlanStorageScope.of(demo = true, apiBaseUrl = "http://test/api/v1"),
                dispatcher,
            ),
            calendarReadRepository = calendar,
        )
        vm.selectCertification(CERTIFICATION_ID)
        vm.setExamDate(LocalDate.of(2026, 12, 14))
        vm.setLevel(CurrentLevel.BEGINNER)
        vm.loadQuestions()
        advanceUntilIdle()
        val questions = vm.form.value.questions
        assertEquals(18, questions.size)
        questions.forEach { vm.answer(it.id, UNKNOWN_KEY) }
        for (day in listOf(Weekday.MONDAY, Weekday.TUESDAY, Weekday.WEDNESDAY, Weekday.THURSDAY, Weekday.FRIDAY)) {
            vm.setDayEnabled(day, true) // the default 18:00–19:00 window
        }

        assertTrue(vm.startCalendarRead())
        assertTrue(vm.calendarReadPermissionPrompted())
        vm.onCalendarReadPermission(true)
        advanceUntilIdle()
        assertEquals(CalendarReadState.Loaded(2, 0, 0), vm.calendarRead.value)
        val imported = vm.form.value
        assertEquals(busy, imported.busySchedules)

        // 25 weekday slots before 2026-12-14, two of them taken by the import.
        assertTrue(generateReviewed(vm))
        advanceUntilIdle()
        assertEquals(GenerationState.DemoShortfall(required = 32, available = 23), vm.generation.value)
        assertEquals(busy, planning.requests.single().busySchedules)

        // An unchanged date keeps the import and the warning.
        vm.setExamDate(LocalDate.of(2026, 12, 14))
        assertEquals(imported, vm.form.value)
        assertEquals(CalendarReadState.Loaded(2, 0, 0), vm.calendarRead.value)
        assertEquals(GenerationState.DemoShortfall(32, 23), vm.generation.value)

        // A later date clears the import. Explicit generation then reads fresh calendar rows.
        vm.setExamDate(LocalDate.of(2027, 1, 4))
        assertEquals(imported.copy(examDate = LocalDate.of(2027, 1, 4), busySchedules = emptyList()), vm.form.value)
        assertEquals(CalendarReadState.Idle, vm.calendarRead.value)
        assertTrue(generateReviewed(vm))
        advanceUntilIdle()

        assertEquals(CalendarReadState.Idle, vm.calendarRead.value)
        assertFalse(vm.calendarReadPermissionPrompted())
        assertEquals(5, calendar.windows.size)
        assertEquals(2, planning.requests.size)
        assertEquals(
            planning.requests[0].copy(examDate = "2027/01/04/00/00"),
            planning.requests[1],
        )
        val success = vm.generation.value as GenerationState.Success
        assertTrue(success.isDemo)
        val rows = success.response.schedules.filter { it.scheduleId.startsWith("study-") }
        assertEquals(34, success.response.schedules.size)
        assertEquals(32, rows.size)
        assertTrue(rows.all { it.scheduleId.startsWith("study-") })
        assertEquals(8, rows.groupingBy { it.topic }.eachCount().size)
        assertTrue(rows.groupingBy { it.topic }.eachCount().values.all { it == 4 })
        // Fresh checking still protects those external intervals.
        assertFalse(rows.any { it.startDate == "2026/11/09/18/00" })
        assertFalse(rows.any { it.startDate == "2026/11/11/18/00" })
        assertTrue(success.response.scheduleCsv.contains("외부 일정"))
        assertEquals(1, vm.plans.value.size)
    }

    private val studyPlan = StudyPlanResponse(
        listOf(ScheduleRow("study-1", "2026/11/02/18/00", "2026/11/02/19/00", "함수")),
        "csv",
    )

    /** Collects the observed export availability like a screen; [latest] is the last value it received. */
    private class Observed(private val scope: TestScope, vm: PlanFormViewModel) {
        private val seen = mutableListOf<Map<Int, ExportBlock?>>()
        init {
            scope.backgroundScope.launch { vm.exportBlocks.collect { seen += it } }
        }
        /** Runs pending collection first, since idling the test does not wait for background work. */
        val latest: Map<Int, ExportBlock?> get() = scope.runCurrent().let { seen.last() }
    }

    /** The screens' reason and a direct start agree; a refused start opens nothing. */
    private fun TestScope.assertExport(vm: PlanFormViewModel, seen: Observed, expected: ExportBlock?) {
        assertEquals(mapOf(1 to expected, 2 to expected), seen.latest)
        if (expected == null) {
            assertTrue(vm.startCalendarExport(1))
            assertFalse(vm.startCalendarExport(1)) // an immediate second tap starts nothing
            vm.closeCalendarExport()
        } else {
            assertFalse(vm.startCalendarExport(1))
            assertFalse(vm.startCalendarExport(2))
            assertNull(vm.calendarExport.value)
        }
    }

    @Test fun exportWaitsForEveryActiveCalendarReadPhaseOnBothPlans() = runTest(dispatcher) {
        val busy = ScheduleRow("17", "2026/11/02/18/00", "2026/11/02/19/00", "외부 일정")
        val calendar = Calendar(CalendarReadOutcome.Loaded(CalendarBusySnapshot(listOf(busy), 0, 0)))
        val (vm, _) = open(calendar, Planning(studyPlan))
        repeat(2) {
            assertTrue(generateReviewed(vm))
            advanceUntilIdle()
        }
        val seen = Observed(this, vm)
        assertExport(vm, seen, null)

        // Loaded: requesting, awaiting the answer, then reading.
        calendar.gate = CompletableDeferred()
        assertTrue(vm.startCalendarRead())
        assertExport(vm, seen, ExportBlock.Busy)
        assertTrue(vm.calendarReadPermissionPrompted())
        assertExport(vm, seen, ExportBlock.Busy)
        vm.onCalendarReadPermission(true)
        assertEquals(CalendarReadState.Loading, vm.calendarRead.value)
        assertExport(vm, seen, ExportBlock.Busy)
        calendar.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(CalendarReadState.Loaded(1, 0, 0), vm.calendarRead.value)
        assertExport(vm, seen, null)

        // Denied at the prompt.
        assertTrue(vm.startCalendarRead())
        assertTrue(vm.calendarReadPermissionPrompted())
        assertExport(vm, seen, ExportBlock.Busy)
        vm.onCalendarReadPermission(false)
        assertEquals(CalendarReadState.PermissionDenied, vm.calendarRead.value)
        assertExport(vm, seen, null)

        // The provider fails or permission is lost while reading.
        for ((outcome, state) in listOf(
            CalendarReadOutcome.Unavailable to CalendarReadState.Unavailable,
            CalendarReadOutcome.PermissionMissing to CalendarReadState.PermissionDenied,
        )) {
            calendar.outcome = outcome
            calendar.gate = CompletableDeferred()
            assertTrue(vm.startCalendarRead())
            vm.onCalendarReadPermission(true)
            assertExport(vm, seen, ExportBlock.Busy)
            calendar.gate!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(state, vm.calendarRead.value)
            assertExport(vm, seen, null)
        }
    }
}
