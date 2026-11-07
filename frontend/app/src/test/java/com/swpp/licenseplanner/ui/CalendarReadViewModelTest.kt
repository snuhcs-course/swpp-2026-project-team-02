package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.BytesPlanStore
import com.swpp.licenseplanner.data.CalendarBusySnapshot
import com.swpp.licenseplanner.data.CalendarReadOutcome
import com.swpp.licenseplanner.data.CalendarReadRepository
import com.swpp.licenseplanner.data.CalendarReadWindow
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
import com.swpp.licenseplanner.domain.Weekday
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private class Planning : PlanningRepository {
        val requests = mutableListOf<StudyPlanRequest>()
        override suspend fun generate(request: StudyPlanRequest): PlanOutcome {
            requests += request
            return PlanOutcome.Success(StudyPlanResponse(emptyList()), false)
        }
    }

    private class Adjustment : AdjustmentRepository {
        override suspend fun adjust(input: AdjustmentInput) = error("not used")
        override suspend fun reload(scheduleCsvFile: String?) = error("not used")
    }

    private class Calendar(var outcome: CalendarReadOutcome) : CalendarReadRepository {
        val windows = mutableListOf<CalendarReadWindow>()
        override suspend fun read(window: CalendarReadWindow): CalendarReadOutcome {
            windows += window
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
        assertTrue(vm.generate())
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
        assertTrue(vm.generate())
        advanceUntilIdle()
        assertTrue(planning.requests.single().busySchedules.isEmpty())

        val (emptyVm, _) = open(calendar)
        assertTrue(emptyVm.startCalendarRead())
        emptyVm.onCalendarReadPermission(true)
        advanceUntilIdle()
        assertEquals(CalendarReadState.Empty, emptyVm.calendarRead.value)
        assertTrue(emptyVm.generate())
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
}
