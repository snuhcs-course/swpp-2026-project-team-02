package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.*
import com.swpp.licenseplanner.data.model.*
import com.swpp.licenseplanner.domain.*
import java.time.LocalDateTime
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FreeTimeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = LocalDateTime.of(2026, 11, 17, 9, 5)
    private val original = RemainingStudyFixtures.original.copy(preparationStart = "2026/11/09/00/00", examDate = "2026/11/23/00/00",
        weeklyAvailability = mapOf("monday" to listOf(TimeWindow("19:00", durationMinutes = 90)),
            "saturday" to listOf(TimeWindow("10:00", durationMinutes = 120))))
    private fun source(demo: Boolean = false) = SessionPlan.register(1, original,
        StudyPlanResponse(listOf(RemainingStudyFixtures.row(1, "2026/11/10/19/00", "2026/11/10/20/00"),
            RemainingStudyFixtures.row(2, "2026/11/11/19/00", "2026/11/11/19/30")), scheduleCsvFile = if (demo) null else "plans/a.csv"), demo)
    private class Calendar : CalendarReadRepository {
        var outcome: CalendarReadOutcome = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0))
        val windows = mutableListOf<CalendarReadWindow>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun read(window: CalendarReadWindow): CalendarReadOutcome { windows += window; gate?.await(); return outcome }
    }
    private class Adjustment(var demo: Boolean) : AdjustmentRepository {
        val inputs = mutableListOf<AdjustmentInput>()
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
            inputs += input
            return if (demo) AdjustmentOutcome.DemoNoSlot else AdjustmentOutcome.Failure(ApiError.InvalidInput("NOT_ENOUGH_TIME: full"))
        }
        override suspend fun reload(scheduleCsvFile: String?) = error("unexpected reload")
    }
    private inner class Batch : RemainingStudyRepository {
        val inputs = mutableListOf<RemainingStudyInput>()
        var error: ApiError? = null
        override suspend fun replan(input: RemainingStudyInput): AdjustmentOutcome {
            inputs += input
            error?.let { return AdjustmentOutcome.Failure(it) }
            if (input.destinations == null) return if (input.source.isDemo) AdjustmentOutcome.DemoNoSlot
                else AdjustmentOutcome.Failure(ApiError.InvalidInput("NOT_ENOUGH_TIME: full"))
            val result = RemainingDestinations.validateDestinations(input.source.current.schedules, input.work.instructions,
                input.destinations, AvailabilityWire.context(original)!!, WireDateTime.parseOrNull(original.preparationStart)!!,
                WireDateTime.parseOrNull(original.examDate)!!, input.replanFrom, now, input.confirmOutside)
            check(result.code == null)
            return AdjustmentOutcome.Success(StudyPlanResponse(result.rows, remainingCsv(result.rows), scheduleCsvFile = input.source.scheduleCsvFile))
        }
    }
    private class Store(private val delegate: LocalPlanStore, private val initial: PlanSnapshot? = null) : LocalPlanStore {
        var fail = false
        override suspend fun load() = delegate.load().let { initial?.let(PlanStoreLoad::Loaded) ?: it }
        override suspend fun save(snapshot: PlanSnapshot) = !fail && delegate.save(snapshot)
    }
    private data class Opened(val vm: PlanFormViewModel, val calendar: Calendar, val adjustment: Adjustment,
                              val batch: Batch, val store: Store)
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()
    private fun TestScope.open(plan: SessionPlan = source()): Opened {
        val scope = PlanStorageScope.of(plan.isDemo, "http://test/api/v1")
        val bytes = MemoryPlanBytes(PlanSnapshotCodec.encode(PlanSnapshot(listOf(plan)), scope).encodeToByteArray())
        val store = Store(BytesPlanStore(bytes, scope, dispatcher),
            if (plan.original.weeklyAvailability.isEmpty()) PlanSnapshot(listOf(plan)) else null)
        val calendar = Calendar(); val adjustment = Adjustment(plan.isDemo); val batch = Batch()
        val vm = PlanFormViewModel(object : QuestionRepository {
            override suspend fun load(certificationId: String) = QuestionOutcome.Loaded(TestFixtures.publicQuestions())
        }, object : PlanningRepository {
            override suspend fun generate(request: StudyPlanRequest): PlanOutcome = error("unexpected generation")
        }, adjustment, today = now.toLocalDate(), planStore = store, calendarReadRepository = calendar,
            remainingStudyRepository = batch, currentMinute = { now }, currentDate = { now.toLocalDate() })
        advanceUntilIdle(); assertTrue(vm.selectPlan(1)); vm.selectFailedSession("study-1")
        return Opened(vm, calendar, adjustment, batch, store)
    }
    private fun TestScope.noFit(o: Opened) { assertTrue(o.vm.submitAdjustment()); advanceUntilIdle(); assertTrue(o.vm.canSearchFreeTime()) }
    private fun TestScope.search(o: Opened) { noFit(o); assertTrue(o.vm.startFreeTimeSearch()); o.vm.onFreeTimePermission(true); advanceUntilIdle() }
    private fun TestScope.remainingNoFit(o: Opened) { assertTrue(o.vm.startRemainingStudy(1)); assertTrue(o.vm.confirmRemainingStudy()); advanceUntilIdle() }
    private fun TestScope.remainingSearch(o: Opened) { remainingNoFit(o); assertTrue(o.vm.startRemainingFreeTimeSearch());
        o.vm.onRemainingFreeTimePermission(true, (o.vm.remainingStudy.value as RemainingStudyState.CalendarSearch).id); advanceUntilIdle() }
    private fun busy(start: LocalDateTime, minutes: Long) = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0,
        listOf(CalendarOccurrence("private-occurrence", "private-series", start, start.plusMinutes(minutes), false))))

    @Test fun offeredOnlyAfterNoFitInEitherFlavorAndNeverWithoutWindows() = runTest(dispatcher) {
        for (demo in listOf(false, true)) {
            val o = open(source(demo)); assertFalse(o.vm.canSearchFreeTime()); assertFalse(o.vm.startFreeTimeSearch())
            noFit(o); assertTrue(o.vm.startFreeTimeSearch()); assertEquals(0, o.calendar.windows.size)
        }
        val o = open(source(true).copy(original = original.copy(weeklyAvailability = emptyMap())))
        assertTrue(o.vm.submitAdjustment()); advanceUntilIdle(); assertFalse(o.vm.canSearchFreeTime())
    }
    @Test fun loadedResultsUseTodayThroughExamAndRequireASelection() = runTest(dispatcher) {
        val o = open(); search(o)
        assertEquals(FreeTimePhase.Results, o.vm.freeTimeSearch.value!!.phase)
        assertEquals(listOf(CalendarReadWindow(now.toLocalDate(), now.toLocalDate().plusDays(7))), o.calendar.windows)
        assertFalse(o.vm.confirmFreeTime()); assertEquals(1, o.adjustment.inputs.size)
    }
    @Test fun denialMissingPermissionUnavailableAndNoTimeNeverSendAMove() = runTest(dispatcher) {
        for ((outcome, phase) in listOf(CalendarReadOutcome.PermissionMissing to FreeTimePhase.PermissionDenied,
            CalendarReadOutcome.Unavailable to FreeTimePhase.Unavailable,
            busy(now.toLocalDate().atStartOfDay(), 7 * 1440) to FreeTimePhase.NoTime)) {
            val o = open(); o.calendar.outcome = outcome; search(o)
            assertEquals(phase, o.vm.freeTimeSearch.value!!.phase); assertEquals(1, o.adjustment.inputs.size)
        }
        val o = open(); noFit(o); o.vm.startFreeTimeSearch(); o.vm.freeTimePermissionPrompted(); o.vm.onFreeTimePermission(false)
        advanceUntilIdle(); assertEquals(FreeTimePhase.PermissionDenied, o.vm.freeTimeSearch.value!!.phase); assertTrue(o.calendar.windows.isEmpty())
    }
    @Test fun confirmationUsesOneManualRequestAndNoCalendarIdentityOrTiming() = runTest(dispatcher) {
        val o = open(); search(o); o.vm.selectFreeTime(0)
        val chosen = o.vm.freeTimeSearch.value!!.suggestions[0]
        assertTrue(o.vm.confirmFreeTime()); assertFalse(o.vm.confirmFreeTime())
        o.vm.onManualPermission(true); advanceUntilIdle()
        assertEquals(2, o.adjustment.inputs.size)
        val sent = o.adjustment.inputs.last()
        assertEquals(ManualMove(chosen.start, chosen.outsideWindows, false), sent.manualMove)
        assertEquals(source().current.schedules, sent.schedules)
        assertFalse(sent.toString().contains("private-occurrence"))
        assertEquals(source().original, sent.original)
    }
    @Test fun newCalendarOverlapGoesThroughExistingManualReview() = runTest(dispatcher) {
        val o = open(); search(o); o.vm.selectFreeTime(0)
        val chosen = o.vm.freeTimeSearch.value!!.suggestions[0]
        o.calendar.outcome = busy(chosen.start, 60); o.vm.confirmFreeTime(); o.vm.onManualPermission(true); advanceUntilIdle()
        assertEquals(ConflictPhase.Review, o.vm.manualReview.value!!.phase)
        assertFalse(o.vm.manualReview.value!!.externalConsent); assertEquals(1, o.adjustment.inputs.size)
    }
    @Test fun changingTargetDiscardsResultsAndLateReads() = runTest(dispatcher) {
        val o = open(); search(o); o.vm.selectFailedSession("study-2"); assertNull(o.vm.freeTimeSearch.value); assertFalse(o.vm.confirmFreeTime())
        val delayed = open(); noFit(delayed); delayed.calendar.gate = CompletableDeferred()
        delayed.vm.startFreeTimeSearch(); delayed.vm.onFreeTimePermission(true); runCurrent()
        delayed.vm.selectFailedSession("study-2"); delayed.calendar.gate!!.complete(Unit); advanceUntilIdle()
        assertNull(delayed.vm.freeTimeSearch.value); assertEquals(1, delayed.adjustment.inputs.size)
    }
    @Test fun remainingSearchIsOfferedOnlyAfterNoFitWithWindowsAndHoldsTheSource() = runTest(dispatcher) {
        val o = open(); assertFalse(o.vm.canSearchRemainingFreeTime()); remainingSearch(o)
        assertTrue(o.vm.remainingStudy.value.holdsSource); assertFalse(o.vm.startManualMove()); assertFalse(o.vm.startCheckIn(1, "study-1"))
        val empty = open(source(true).copy(original = original.copy(weeklyAvailability = emptyMap())))
        remainingNoFit(empty); assertFalse(empty.vm.canSearchRemainingFreeTime())
    }
    @Test fun remainingDenialAndUnavailableNeverSubmitAConfirmedBatch() = runTest(dispatcher) {
        for (outcome in listOf(CalendarReadOutcome.PermissionMissing, CalendarReadOutcome.Unavailable)) {
            val o = open(); o.calendar.outcome = outcome; remainingSearch(o)
            assertTrue((o.vm.remainingStudy.value as RemainingStudyState.CalendarSearch).placements.isEmpty())
            assertEquals(1, o.batch.inputs.size)
        }
        val o = open(); remainingNoFit(o); o.vm.startRemainingFreeTimeSearch()
        o.vm.onRemainingFreeTimePermission(false, (o.vm.remainingStudy.value as RemainingStudyState.CalendarSearch).id)
        assertTrue(o.calendar.windows.isEmpty()); assertEquals(1, o.batch.inputs.size)
    }
    @Test fun changedCalendarRequiresAnotherConfirmationThenSavesOneBatch() = runTest(dispatcher) {
        val o = open(); remainingSearch(o)
        val before = o.vm.remainingStudy.value as RemainingStudyState.CalendarSearch
        o.calendar.outcome = busy(WireDateTime.parseOrNull(before.placements.first().row.startDate)!!, 60)
        assertTrue(o.vm.confirmRemainingFreeTime()); assertFalse(o.vm.confirmRemainingFreeTime()); advanceUntilIdle()
        val after = o.vm.remainingStudy.value as RemainingStudyState.CalendarSearch
        assertTrue(after.changed); assertNotEquals(before.placements, after.placements); assertEquals(1, o.batch.inputs.size)
        assertTrue(o.vm.confirmRemainingFreeTime()); advanceUntilIdle()
        val success = o.vm.remainingStudy.value as RemainingStudyState.Success
        assertEquals(MoveSave.Saved, success.save); assertEquals(2, o.batch.inputs.size)
        val sent = o.batch.inputs.last(); assertTrue(sent.confirmOutside); assertEquals(2, sent.destinations!!.size)
        assertFalse(sent.toString().contains("private-occurrence")); assertEquals(original, o.vm.plans.value.single().original)
    }
    @Test fun remainingPreSaveFailureSendsNothingAndUncertainReplyRequiresReload() = runTest(dispatcher) {
        val o = open(); remainingSearch(o); o.store.fail = true
        o.vm.confirmRemainingFreeTime(); advanceUntilIdle()
        assertTrue(o.vm.remainingStudy.value is RemainingStudyState.Invalid); assertEquals(1, o.batch.inputs.size)
        val uncertain = open(); remainingSearch(uncertain); uncertain.batch.error = ApiError.Connection
        uncertain.vm.confirmRemainingFreeTime(); advanceUntilIdle()
        assertEquals(PlanRecovery.ReloadRequired, uncertain.vm.plans.value.single().recovery)
        assertFalse(uncertain.vm.canSearchRemainingFreeTime())
    }
    @Test fun unsupportedServerAndMovementRejectionsNeverRetryAutomatically() = runTest(dispatcher) {
        for (error in listOf(ApiError.InvalidInput("each remaining_sessions item must contain only schedule_id, remaining_minutes and replacement_schedule_id"),
            ApiError.InvalidInput("confirm_outside_availability is not used when mode is remaining"),
            ApiError.InvalidInput("mode must be automatic or manual"),
            ApiError.ManualMoveRejected(ManualMoveRejection.StudySessionOverlap))) {
            val o = open(); remainingSearch(o); o.batch.error = error
            o.vm.confirmRemainingFreeTime(); advanceUntilIdle(); assertEquals(2, o.batch.inputs.size)
            assertEquals(error is ApiError.ManualMoveRejected, o.vm.canSearchRemainingFreeTime())
            assertEquals(if (error is ApiError.ManualMoveRejected) "시간을 다시 확인해야 해요" else "서버 업데이트가 필요해요", remainingFailureMessage(error))
        }
    }
}
