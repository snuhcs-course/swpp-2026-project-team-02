package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.*
import com.swpp.licenseplanner.data.model.*
import com.swpp.licenseplanner.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarKeepViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val day = LocalDate.of(2031, 3, 3)
    private var now = day.atTime(12, 0)
    private val request = (RequestMapper.map(TestFixtures.completeState(start = day, exam = day.plusWeeks(6))) as MappingResult.Ready).request
    private val row = ScheduleRow("study-1", "2031/03/03/18/00", "2031/03/03/19/00", "공부")
    private fun plan(n: Int) = SessionPlan.register(n, request, StudyPlanResponse(listOf(row.copy(
        startDate = if (n == 1) row.startDate else "2031/03/03/20/00",
        endDate = if (n == 1) row.endDate else "2031/03/03/21/00")), scheduleCsvFile = "file-$n"), false)
        .copy(checkIns = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Done, 20)))
    private val draft = CreationDraft(TestFixtures.completeState(start = day, exam = day.plusWeeks(6)), CreationStep.QUESTIONS, 1)
    private val source = PlanSnapshot(listOf(plan(1), plan(2)), creationDraft = draft)

    private class Store(var snapshot: PlanSnapshot) : LocalPlanStore {
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        val failAttempts = mutableSetOf<Int>()
        val attempts = mutableListOf<PlanSnapshot>()
        override suspend fun load() = PlanStoreLoad.Loaded(snapshot)
        override suspend fun save(snapshot: PlanSnapshot): Boolean {
            attempts += snapshot
            gate?.await()
            if (fail || attempts.size in failAttempts) return false
            this.snapshot = snapshot
            return true
        }
    }
    private class Reader : CalendarReadRepository {
        var outcome: CalendarReadOutcome = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0))
        var gate: CompletableDeferred<Unit>? = null
        val windows = mutableListOf<CalendarReadWindow>()
        val results = ArrayDeque<CalendarReadOutcome>()
        override suspend fun read(window: CalendarReadWindow): CalendarReadOutcome {
            windows += window
            val result = results.removeFirstOrNull() ?: outcome
            gate?.await()
            return result
        }
    }
    private val inputs = mutableListOf<AdjustmentInput>()
    private var forced: AdjustmentOutcome? = null
    private var generated = 0
    private val adjustment = object : AdjustmentRepository {
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
            inputs += input
            return forced ?: DemoAdjustmentRepository { now }.adjust(input)
        }
        override suspend fun reload(scheduleCsvFile: String?) = ReloadOutcome.Failure(ApiError.Connection)
    }
    private fun model(store: Store, reader: Reader) = PlanFormViewModel(
        questionRepository = object : QuestionRepository {
            override suspend fun load(certificationId: String) = error("Unexpected question read")
        },
        planningRepository = object : PlanningRepository {
            override suspend fun generate(request: StudyPlanRequest): PlanOutcome {
                generated++
                return PlanOutcome.Success(StudyPlanResponse(listOf(row), scheduleCsvFile = "new-file"), false)
            }
        },
        adjustmentRepository = adjustment, today = day, planStore = store, calendarReadRepository = reader,
        currentMinute = { now }, currentDate = { day.plusYears(1) },
    )
    private fun busy(hour: Int = 18, key: String = "event", second: Int = 0): CalendarReadOutcome {
        val occurrence = CalendarOccurrence(key, "series", day.atTime(hour, 30, second), day.atTime(hour + 1, 0), false)
        return CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 1, 0, listOf(occurrence)))
    }
    private fun TestScope.foreground(vm: PlanFormViewModel) {
        vm.enterForeground(true)
        advanceUntilIdle()
    }
    private fun TestScope.manual(vm: PlanFormViewModel, hour: Int = 14) {
        advanceUntilIdle()
        assertTrue(vm.selectPlan(1))
        vm.selectFailedSession("study-1")
        assertTrue(vm.startManualMove())
        vm.setManualStart(day.plusDays(1).atTime(hour, 0))
        assertTrue(vm.submitManualMove())
        vm.onManualPermission(true)
        advanceUntilIdle()
    }
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun foregroundCoalescesRangesAndIsReadOnlyPlanScopedAndRotationSafe() = runTest(dispatcher) {
        val store = Store(source)
        val reader = Reader().apply { outcome = busy() }
        val vm = model(store, reader)
        foreground(vm)
        assertEquals(1, reader.windows.size)
        assertEquals(1, vm.notices.value.getValue(1).conflicts.matches.size)
        assertTrue(vm.notices.value.getValue(2).conflicts.matches.isEmpty())
        vm.enterForeground(true)
        advanceUntilIdle()
        assertEquals(1, reader.windows.size)
        assertEquals(source, store.snapshot)
        assertTrue(store.attempts.isEmpty() && inputs.isEmpty())
        vm.leaveForeground()
        foreground(vm)
        assertEquals(2, reader.windows.size)
    }

    @Test fun missingPermissionNeverQueriesAndExplicitCheckIsSeparate() = runTest(dispatcher) {
        val reader = Reader()
        val store = Store(source)
        val vm = model(store, reader)
        vm.enterForeground(false)
        advanceUntilIdle()
        assertTrue(reader.windows.isEmpty())
        assertNull(vm.noticePermission.value)
        assertEquals(NoticePhase.Unchecked, vm.notices.value.getValue(1).phase)
        assertTrue(vm.requestNoticeCheck())
        vm.noticePermissionPrompted()
        vm.onNoticePermission(true)
        advanceUntilIdle()
        assertEquals(NoticePhase.Checked, vm.notices.value.getValue(1).phase)
        assertTrue(store.attempts.isEmpty())
    }

    @Test fun failedOrRevokedRefreshRetainsOnlyStaleResultsAndCannotKeep() = runTest(dispatcher) {
        for (outcome in listOf(CalendarReadOutcome.Unavailable, CalendarReadOutcome.PermissionMissing)) {
            val reader = Reader().apply { this.outcome = busy() }
            val vm = model(Store(source), reader)
            foreground(vm)
            reader.outcome = outcome
            vm.leaveForeground()
            foreground(vm)
            assertEquals(NoticePhase.Stale, vm.notices.value.getValue(1).phase)
            assertFalse(vm.keepCurrentConflict(1, "study-1"))
        }
    }

    @Test fun endedIntervalsAreExcludedAndRunningOvernightSessionIsIncluded() = runTest(dispatcher) {
        val overnight = row.copy(startDate = "2031/03/02/23/30", endDate = "2031/03/03/12/30")
        val store = Store(source.copy(plans = listOf(plan(1).copy(current = StudyPlanResponse(listOf(overnight))))))
        val reader = Reader().apply { outcome = busy(12) }
        val vm = model(store, reader)
        foreground(vm)
        assertEquals(day, reader.windows.single().start)
        now = day.atTime(13, 0)
        vm.leaveForeground()
        foreground(vm)
        assertTrue(vm.notices.value.getValue(1).conflicts.matches.isEmpty())
        assertEquals(1, reader.windows.size)
    }

    @Test fun lateForegroundReadCannotPublishAfterBackgroundAndNewEntryGetsFreshKeys() = runTest(dispatcher) {
        val reader = Reader().apply { outcome = busy(); gate = CompletableDeferred() }
        val vm = model(Store(source), reader)
        vm.enterForeground(true)
        runCurrent()
        vm.leaveForeground()
        reader.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.notices.value.values.all { it.conflicts.matches.isEmpty() })
        reader.gate = null
        reader.outcome = busy(key = "changed", second = 1)
        foreground(vm)
        assertEquals("changed", vm.notices.value.getValue(1).conflicts.matches.single().occurrence.key)
    }

    @Test fun exactKeepPersistsReportsOtherPlanAndDraftWithoutAnyRequest() = runTest(dispatcher) {
        val store = Store(source)
        val reader = Reader().apply { outcome = busy() }
        val vm = model(store, reader)
        foreground(vm)
        assertTrue(vm.keepCurrentConflict(1, "study-1"))
        assertFalse(vm.keepCurrentConflict(1, "study-1"))
        advanceUntilIdle()
        assertEquals(source.plans[1], store.snapshot.plans[1])
        assertEquals(source.creationDraft, store.snapshot.creationDraft)
        assertEquals(source.plans[0].checkIns, store.snapshot.plans[0].checkIns)
        assertEquals(64, store.snapshot.plans[0].keptConflicts.getValue("study-1").single().length)
        assertTrue(inputs.isEmpty())
        val reopened = model(store, reader)
        foreground(reopened)
        assertTrue(reopened.notices.value.getValue(1).conflicts.matches.isEmpty())
        reader.outcome = busy(key = "same-event-edited", second = 1)
        reopened.leaveForeground()
        foreground(reopened)
        assertEquals(1, reopened.notices.value.getValue(1).conflicts.matches.size)
    }

    @Test fun failedHiddenKeepOnBBlocksAllMutationsUntilExactLocalRetryThenExplicitReentry() = runTest(dispatcher) {
        val reader = Reader().apply { outcome = busy(20) }
        val store = Store(source)
        val vm = model(store, reader)
        foreground(vm)
        store.fail = true
        assertTrue(vm.keepCurrentConflict(2, "study-1"))
        advanceUntilIdle()
        val candidate = store.attempts.single()
        assertEquals(MoveSave.Unsaved, vm.keepFeedback.value!!.save)
        assertEquals(1, vm.notices.value.getValue(2).conflicts.matches.size)
        vm.closeKeepFeedback()
        assertFalse(vm.startRegeneration(1))
        assertTrue(vm.selectPlan(1))
        vm.selectFailedSession("study-1")
        assertFalse(vm.startManualMove())
        assertFalse(vm.keepCurrentConflict(1, "study-1"))
        vm.retrySave()
        advanceUntilIdle()
        assertEquals(candidate, store.attempts.last())
        store.fail = false
        vm.retrySave()
        vm.retrySave()
        advanceUntilIdle()
        assertEquals(3, store.attempts.size)
        assertEquals(candidate, store.snapshot)
        assertNull(vm.keepFeedback.value)
        assertEquals(0, generated)
        assertEquals(1, reader.windows.size)
        assertTrue(vm.startRegeneration(1))
        assertEquals(ConflictPhase.ReplacementConsent, vm.calendarConflict.value!!.phase)
    }

    @Test fun keepRetryDoesNotSettleSavedReportAndLateCompletionDoesNotReopenFeedback() = runTest(dispatcher) {
        val reader = Reader().apply { outcome = busy() }
        val store = Store(source)
        val vm = model(store, reader)
        foreground(vm)
        assertTrue(vm.startCheckIn(2, "study-1"))
        vm.chooseCheckInStatus(StudyCheckInStatus.Done)
        vm.setCheckInMinutes("35")
        assertTrue(vm.saveCheckIn())
        advanceUntilIdle()
        val report = vm.checkIn.value
        store.fail = true
        assertTrue(vm.keepCurrentConflict(1, "study-1"))
        advanceUntilIdle()
        store.fail = false
        store.gate = CompletableDeferred()
        vm.closeKeepFeedback()
        vm.retrySave()
        runCurrent()
        assertEquals(report, vm.checkIn.value)
        store.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(report, vm.checkIn.value)
        assertNull(vm.keepFeedback.value)
        assertEquals(35, store.snapshot.plans[1].checkIns.getValue("study-1").actualMinutes)
    }

    @Test fun freshManualConflictsRequireConsentAndChangedFinalReadRequiresReviewAgain() = runTest(dispatcher) {
        val reader = Reader()
        val event = (busy() as CalendarReadOutcome.Loaded).snapshot.occurrences.single().copy(
            start = day.plusDays(1).atTime(14, 0), end = day.plusDays(1).atTime(15, 0))
        reader.outcome = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0, listOf(event)))
        val store = Store(source)
        val vm = model(store, reader)
        manual(vm)
        assertTrue(inputs.isEmpty() && store.attempts.isEmpty())
        reader.outcome = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0, listOf(event.copy(key = "changed"))))
        assertTrue(vm.confirmManualExternalOverlap())
        advanceUntilIdle()
        assertTrue(inputs.isEmpty())
        assertFalse(vm.manualReview.value!!.externalConsent)
        assertTrue(vm.confirmManualExternalOverlap())
        advanceUntilIdle()
        assertEquals(1, inputs.size)
        assertTrue(inputs.single().manualMove!!.confirmExternalOverlap)
        assertFalse(inputs.single().manualMove!!.confirmOutsideAvailability)
        assertTrue(vm.adjustment.value.canConfirmOutsideAvailability)
        assertTrue(vm.confirmOutsideAvailability())
        vm.onManualPermission(true)
        advanceUntilIdle()
        assertEquals(2, inputs.size)
        assertTrue(inputs.last().manualMove!!.confirmOutsideAvailability)
        assertTrue(inputs.last().manualMove!!.confirmExternalOverlap)
        assertEquals("2031/03/04/14/00", store.snapshot.plans[0].current.schedules.first().startDate)
        assertEquals(1, store.snapshot.plans[0].keptConflicts.size)
        assertEquals(source.plans[0].checkIns, store.snapshot.plans[0].checkIns)
    }

    @Test fun uncheckedManualWithRetainedConflictStillNeedsExternalConsentAndCancellationSendsNothing() = runTest(dispatcher) {
        val external = ScheduleRow("external-1", "2031/03/04/14/00", "2031/03/04/15/00", "")
        val retained = source.copy(plans = listOf(plan(1).copy(current = StudyPlanResponse(listOf(row, external))), plan(2)))
        val reader = Reader().apply { outcome = CalendarReadOutcome.Unavailable }
        val store = Store(retained)
        val vm = model(store, reader)
        manual(vm)
        assertEquals(ConflictPhase.Unchecked, vm.manualReview.value!!.phase)
        assertTrue(vm.continueManualUnchecked())
        assertEquals(ConflictPhase.Review, vm.manualReview.value!!.phase)
        assertTrue(inputs.isEmpty())
        vm.cancelManualReview()
        assertEquals(retained, store.snapshot)
        assertTrue(store.attempts.isEmpty())
    }

    @Test fun outsideConsentCanPrecedeExternalReviewAndDestinationEditClearsBoth() = runTest(dispatcher) {
        val store = Store(source)
        val reader = Reader()
        val vm = model(store, reader)
        manual(vm)
        assertTrue(vm.adjustment.value.canConfirmOutsideAvailability)
        val destination = day.plusDays(1).atTime(14, 0)
        reader.outcome = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0,
            listOf(CalendarOccurrence("new", null, destination, destination.plusHours(1), false))))
        assertTrue(vm.confirmOutsideAvailability())
        vm.onManualPermission(true)
        advanceUntilIdle()
        assertEquals(1, inputs.size)
        assertTrue(vm.manualReview.value!!.outsideConsent)
        assertFalse(vm.manualReview.value!!.externalConsent)
        assertTrue(vm.confirmManualExternalOverlap())
        advanceUntilIdle()
        assertEquals(ManualMove(destination, true, true), inputs.last().manualMove)
        assertEquals(2, inputs.size)
        vm.selectFailedSession("study-1")
        vm.startManualMove()
        vm.setManualStart(destination.plusHours(2))
        vm.submitManualMove()
        assertFalse(vm.manualReview.value!!.outsideConsent)
        assertFalse(vm.manualReview.value!!.externalConsent)
    }

    @Test fun destinationEditClearsExternalConsentAndFailedPresaveSendsNothing() = runTest(dispatcher) {
        val reader = Reader()
        val store = Store(source)
        val vm = model(store, reader)
        advanceUntilIdle()
        vm.selectPlan(1)
        vm.selectFailedSession("study-1")
        vm.startManualMove()
        vm.setManualStart(day.plusDays(1).atTime(14, 0))
        store.fail = true
        assertTrue(vm.submitManualMove())
        vm.onManualPermission(true)
        advanceUntilIdle()
        assertTrue(inputs.isEmpty())
        assertEquals(source, store.snapshot)
        assertFalse(vm.submitManualMove())
    }

    @Test fun uncertainResponseRecordsNoKeepAndRequiresReloadWithoutReplay() = runTest(dispatcher) {
        val reader = Reader()
        val store = Store(source)
        val vm = model(store, reader)
        forced = AdjustmentOutcome.Failure(ApiError.Connection)
        manual(vm)
        assertEquals(1, inputs.size)
        assertEquals(PlanRecovery.ReloadRequired, vm.plans.value.first().recovery)
        assertTrue(vm.plans.value.first().keptConflicts.isEmpty())
        vm.retryAdjustment()
        advanceUntilIdle()
        assertEquals(1, inputs.size)
    }

    @Test fun pureKeepRetryLeavesAnUnrelatedSavedMoveUnchanged() = runTest(dispatcher) {
        val store = Store(source)
        val reader = Reader().apply { outcome = busy() }
        val vm = model(store, reader)
        foreground(vm)
        vm.selectPlan(2)
        vm.selectFailedSession("study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        val move = vm.adjustment.value
        assertEquals(MoveSave.Saved, (move.state as AdjustmentState.Success).save)
        val reads = reader.windows.size
        store.fail = true
        assertTrue(vm.keepCurrentConflict(1, "study-1"))
        advanceUntilIdle()
        val candidate = store.attempts.last()
        store.fail = false
        vm.retrySave()
        advanceUntilIdle()
        assertEquals(move, vm.adjustment.value)
        assertEquals(candidate, store.snapshot)
        assertEquals(reads, reader.windows.size)
        assertEquals(1, inputs.size)
    }

    @Test fun movedKeepRetrySettlesOnlyItsCapturedMoveAndKeepAndNeverReplaysTheMove() = runTest(dispatcher) {
        val store = Store(source).apply { failAttempts += 3 }
        val reader = Reader()
        val start = day.plusWeeks(1).atTime(18, 0)
        val occurrence = CalendarOccurrence("destination", null, start, start.plusHours(1), false)
        reader.outcome = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0, listOf(occurrence)))
        val vm = model(store, reader)
        advanceUntilIdle()
        vm.startCheckIn(2, "study-1")
        vm.chooseCheckInStatus(StudyCheckInStatus.Done)
        vm.setCheckInMinutes("35")
        vm.saveCheckIn()
        advanceUntilIdle()
        val report = vm.checkIn.value
        val savedB = store.snapshot.plans[1]
        vm.selectPlan(1)
        vm.selectFailedSession("study-1")
        vm.startManualMove()
        vm.setManualStart(start)
        vm.submitManualMove()
        vm.onManualPermission(true)
        advanceUntilIdle()
        assertTrue(vm.confirmManualExternalOverlap())
        advanceUntilIdle()
        assertEquals(1, inputs.size)
        assertEquals(MoveSave.Unsaved, vm.keepFeedback.value!!.save)
        assertEquals(MoveSave.Unsaved, (vm.adjustment.value.state as AdjustmentState.Success).save)
        val candidate = store.attempts.last()
        val reads = reader.windows.size
        store.gate = CompletableDeferred()
        vm.retrySave()
        vm.retrySave()
        runCurrent()
        assertEquals(MoveSave.Saving, vm.keepFeedback.value!!.save)
        assertEquals(MoveSave.Saving, (vm.adjustment.value.state as AdjustmentState.Success).save)
        assertEquals(report, vm.checkIn.value)
        store.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(MoveSave.Saved, vm.keepFeedback.value!!.save)
        assertEquals(MoveSave.Saved, (vm.adjustment.value.state as AdjustmentState.Success).save)
        assertEquals(candidate, store.snapshot)
        assertEquals(4, store.attempts.size)
        assertEquals(savedB, store.snapshot.plans[1])
        assertEquals(report, vm.checkIn.value)
        assertEquals(source.creationDraft, store.snapshot.creationDraft)
        assertEquals(reads, reader.windows.size)
        assertEquals(1, inputs.size)
    }

    @Test fun replacementClearsOnlySelectedKeepsAndReportsAtDurableCommit() = runTest(dispatcher) {
        val kept = source.copy(plans = source.plans.map { it.copy(keptConflicts = mapOf("study-1" to setOf("a".repeat(64)))) })
        val store = Store(kept)
        val reader = Reader()
        val vm = model(store, reader)
        advanceUntilIdle()
        assertTrue(vm.startRegeneration(1))
        vm.confirmRegeneration()
        store.fail = true
        vm.onConflictPermission(true)
        advanceUntilIdle()
        assertEquals(kept, store.snapshot)
        assertEquals(kept.plans, vm.plans.value)
        store.fail = false
        vm.retrySave()
        advanceUntilIdle()
        assertTrue(store.snapshot.plans[0].keptConflicts.isEmpty())
        assertTrue(store.snapshot.plans[0].checkIns.isEmpty())
        assertEquals(kept.plans[1], store.snapshot.plans[1])
        assertEquals(draft, store.snapshot.creationDraft)
        assertEquals(1, generated)
    }

    @Test fun foregroundEntryDefersDuringAnExistingLocalWrite() = runTest(dispatcher) {
        val store = Store(source)
        val reader = Reader()
        val vm = model(store, reader)
        advanceUntilIdle()
        vm.startCheckIn(1, "study-1")
        vm.chooseCheckInStatus(StudyCheckInStatus.Missed)
        store.gate = CompletableDeferred()
        vm.saveCheckIn()
        runCurrent()
        vm.enterForeground(true)
        runCurrent()
        assertTrue(reader.windows.isEmpty())
        store.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, reader.windows.size)
        assertEquals(1, store.attempts.size)
    }
}
