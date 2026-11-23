package com.swpp.licenseplanner.ui

import androidx.lifecycle.viewModelScope
import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.*
import com.swpp.licenseplanner.data.model.*
import com.swpp.licenseplanner.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemainingStudyViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fixture = RemainingStudyFixtures
    private val scope = PlanStorageScope.of(false, "http://test/api/v1")
    private val a = fixture.plan(false).copy(keptConflicts = mapOf("study-60" to setOf("a".repeat(64))))
    private val b = a.copy(ordinal = 2, scheduleCsvFile = "plans/b.csv", current = a.current.copy(scheduleCsvFile = "plans/b.csv"),
        checkIns = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Partly, 5)),
        keptConflicts = mapOf("study-70" to setOf("b".repeat(64))))
    private val draft = CreationDraft(TestFixtures.completeState(), CreationStep.AVAILABILITY, 0, null)

    private inner class Store(val disk: MemoryPlanBytes, planScope: String, private var snapshot: PlanSnapshot?) : LocalPlanStore {
        val real = BytesPlanStore(disk, planScope, dispatcher)
        var count = 0
        var gate: CompletableDeferred<Unit>? = null
        val fail = mutableSetOf<Int>()
        override suspend fun load() = snapshot?.let { PlanStoreLoad.Loaded(it) } ?: real.load()
        override suspend fun save(snapshot: PlanSnapshot): Boolean {
            val number = ++count
            gate?.await()
            if (number in fail) return false
            if (this.snapshot == null) return real.save(snapshot)
            this.snapshot = snapshot
            return true
        }
    }

    private inner class Batch : RemainingStudyRepository {
        val inputs = mutableListOf<RemainingStudyInput>()
        var gate: CompletableDeferred<Unit>? = null
        var error: ApiError? = null
        var alter: (StudyPlanResponse) -> StudyPlanResponse = { it }
        override suspend fun replan(input: RemainingStudyInput): AdjustmentOutcome {
            inputs += input
            gate?.await()
            error?.let { return AdjustmentOutcome.Failure(it) }
            val rows = if (input.destinations == null) {
                RemainingStudy.place(input.source, input.work, input.replanFrom, fixture.now) ?: return AdjustmentOutcome.DemoNoSlot
            } else {
                val source = input.source
                val result = RemainingDestinations.validateDestinations(source.current.schedules, input.work.instructions, input.destinations,
                    requireNotNull(AvailabilityWire.context(source.original)),
                    requireNotNull(WireDateTime.parseOrNull(source.original.preparationStart)),
                    requireNotNull(WireDateTime.parseOrNull(source.original.examDate)), input.replanFrom, fixture.now, input.confirmOutside)
                check(result.code == null) { "unexpected destination rejection: ${result.code}" }
                result.rows
            }
            return AdjustmentOutcome.Success(alter(StudyPlanResponse(rows, remainingCsv(rows), scheduleCsvFile = input.source.scheduleCsvFile,
                availabilitySnapshot = AvailabilitySnapshot(input.source.original.weeklyAvailability, input.source.original.availabilityOverrides))))
        }
    }

    private class Other : AdjustmentRepository {
        var mutations = 0
        var reloads = 0
        var rows: List<ScheduleRow> = emptyList()
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome { mutations++; error("unexpected single move") }
        override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome { reloads++; return ReloadOutcome.Loaded(rows) }
    }

    private inner class Calendar : CalendarReadRepository, CalendarExportRepository {
        var reads = 0
        var writes = 0
        var gate: CompletableDeferred<Unit>? = null
        var outcome: CalendarReadOutcome = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0))
        override suspend fun read(window: CalendarReadWindow): CalendarReadOutcome {
            reads++; gate?.await(); return outcome
        }
        override suspend fun calendars(): CalendarListOutcome { reads++; return CalendarListOutcome.Unavailable }
        override suspend fun export(destination: CalendarDestination, events: List<CalendarEvent>): CalendarWriteOutcome {
            writes++; error("unexpected calendar write")
        }
    }

    private data class Opened(val vm: PlanFormViewModel, val store: Store, val batch: Batch, val other: Other, val calendar: Calendar)

    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    private fun TestScope.open(snapshot: PlanSnapshot = PlanSnapshot(listOf(a, b), creationDraft = draft),
                               disk: MemoryPlanBytes? = null, inMemory: Boolean = false): Opened {
        val demo = snapshot.plans.firstOrNull()?.isDemo == true
        val planScope = if (demo) PlanStorageScope.DEMO else scope
        val bytes = disk ?: MemoryPlanBytes(PlanSnapshotCodec.encode(snapshot, planScope).encodeToByteArray())
        val store = Store(bytes, planScope, snapshot.takeIf { inMemory })
        val batch = Batch()
        val other = Other()
        val calendar = Calendar()
        val vm = PlanFormViewModel(object : QuestionRepository {
            override suspend fun load(certificationId: String) = QuestionOutcome.Loaded(TestFixtures.publicQuestions())
        }, object : PlanningRepository {
            override suspend fun generate(request: StudyPlanRequest): PlanOutcome = error("unexpected generation")
        }, other, today = fixture.now.toLocalDate(), planStore = store, calendarReadRepository = calendar,
            calendarExportRepository = calendar, remainingStudyRepository = if (demo) DemoRemainingStudyRepository { fixture.now } else batch,
            currentMinute = { fixture.now },
            currentDate = { fixture.now.toLocalDate() })
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, vm.storage.value)
        return Opened(vm, store, batch, other, calendar)
    }

    private suspend fun restored(opened: Opened): PlanSnapshot =
        (opened.store.load() as PlanStoreLoad.Loaded).snapshot

    private fun TestScope.saveFollowUpReport(opened: Opened, status: StudyCheckInStatus, minutes: String? = null) {
        val vm = opened.vm
        assertTrue(vm.startCheckIn(1, "study-4"))
        vm.chooseCheckInStatus(status)
        minutes?.let(vm::setCheckInMinutes)
        assertTrue(vm.saveCheckIn())
        advanceUntilIdle()
        assertEquals(CheckInSave.Saved, vm.checkIn.value?.save)
    }

    private fun TestScope.searchFollowUp(opened: Opened, granted: Boolean = true): RemainingStudyState.CalendarSearch {
        val vm = opened.vm
        assertTrue(vm.startCheckInFollowUp())
        val search = vm.remainingStudy.value as RemainingStudyState.CalendarSearch
        vm.onRemainingFreeTimePermission(granted, search.id)
        advanceUntilIdle()
        return vm.remainingStudy.value as RemainingStudyState.CalendarSearch
    }

    @Test fun missedReportOffersOnlyItsOwnTimeAndLaterKeepsTheSavedCollection() = runTest(dispatcher) {
        val today = fixture.row(4, "2026/11/16/16/00", "2026/11/16/17/00")
        val source = a.copy(current = a.current.copy(schedules = a.current.schedules.dropLast(1) + today))
        val o = open(PlanSnapshot(listOf(source, b), creationDraft = draft))
        saveFollowUpReport(o, StudyCheckInStatus.Missed)
        val followUp = requireNotNull(o.vm.checkIn.value?.followUp)
        assertEquals(today, followUp.source)
        assertEquals(60, followUp.remainingMinutes)
        assertEquals("study-61", followUp.replacementId)
        assertEquals(0, o.calendar.reads)
        assertTrue(o.batch.inputs.isEmpty())
        assertEquals(0, o.other.mutations)
        val saved = restored(o)
        val bytes = o.store.disk.committed!!.copyOf()
        o.vm.closeCheckIn()
        assertNull(o.vm.checkIn.value)
        assertFalse(o.vm.startCheckInFollowUp())
        assertArrayEquals(bytes, o.store.disk.committed)
        assertEquals(source.current, saved.plans.first().current)
        assertEquals(StudyCheckIn(StudyCheckInStatus.Missed, 0), saved.plans.first().checkInFor(today.scheduleId))
        assertEquals(b, saved.plans[1])
        assertEquals(draft, saved.creationDraft)
        assertTrue(o.vm.startRemainingStudy(1))
    }

    @Test fun partlyOffersOnlyPositiveRemaindersAndDoneOffersNone() = runTest(dispatcher) {
        val o = open()
        for ((status, minutes) in listOf(StudyCheckInStatus.Partly to "40", StudyCheckInStatus.Partly to "60",
                StudyCheckInStatus.Partly to "75", StudyCheckInStatus.Done to "40")) {
            saveFollowUpReport(o, status, minutes)
            assertEquals(20.takeIf { status == StudyCheckInStatus.Partly && minutes == "40" },
                o.vm.checkIn.value?.followUp?.remainingMinutes)
            o.vm.closeCheckIn()
        }
        assertEquals(0, o.calendar.reads)
        assertTrue(o.batch.inputs.isEmpty())
    }

    @Test fun noWindowsLegacyAndReloadRequiredPlansStillSaveTheReportWithoutAnOffer() = runTest(dispatcher) {
        val withoutWindows = a.copy(original = a.original.copy(weeklyAvailability = emptyMap()))
        val legacy = a.copy(scheduleCsvFile = null, current = a.current.copy(scheduleCsvFile = null))
        for (source in listOf(withoutWindows, legacy, a.copy(recovery = PlanRecovery.ReloadRequired))) {
            val o = open(PlanSnapshot(listOf(source)), inMemory = source == withoutWindows)
            saveFollowUpReport(o, StudyCheckInStatus.Missed)
            assertNull(o.vm.checkIn.value?.followUp)
            assertEquals(StudyCheckIn(StudyCheckInStatus.Missed, 0), restored(o).plans.single().checkInFor("study-4"))
            assertEquals(0, o.calendar.reads)
            assertTrue(o.batch.inputs.isEmpty())
        }
    }

    @Test fun followUpSendsOneChosenTimeAndKeepsTheOriginalReportAndOtherRows() = runTest(dispatcher) {
        val o = open()
        saveFollowUpReport(o, StudyCheckInStatus.Missed)
        val search = searchFollowUp(o)
        val dates = listOf("2026/11/16/18/00", "2026/11/17/18/00", "2026/11/18/18/00")
        assertEquals(dates, search.suggestions.map { WireDateTime.format(it.start) })
        assertTrue(search.suggestions.all { it.end == it.start.plusHours(1) && !it.outsideWindows })
        assertEquals(a.current.schedules.last().topic, search.input.work.contributions.single().source.topic)
        assertFalse(o.vm.confirmRemainingFreeTime())
        assertFalse(o.vm.startCheckIn(2, "study-1"))
        assertFalse(o.vm.selectPlan(2))
        o.vm.selectFollowUpTime(1)
        assertTrue(o.vm.confirmRemainingFreeTime()); assertFalse(o.vm.confirmRemainingFreeTime())
        advanceUntilIdle()
        val sent = o.batch.inputs.single()
        assertEquals(listOf(RemainingSession("study-4", 60, "study-61")), sent.work.instructions)
        assertEquals(mapOf("study-4" to search.suggestions[1].start), sent.destinations)
        assertFalse(sent.confirmOutside)
        assertEquals(2, o.calendar.reads)
        assertEquals(0, o.calendar.writes)
        val plan = restored(o).plans.first()
        val new = plan.current.schedules.single { it.scheduleId == "study-61" }
        assertEquals(dates[1], new.startDate)
        assertEquals(a.current.schedules.dropLast(1), plan.current.schedules.filterNot { it == new })
        assertEquals(StudyCheckIn(StudyCheckInStatus.Missed, 0), plan.checkIns["study-4"])
        assertNull(plan.checkInFor("study-61"))
    }

    @Test fun shortRemainderNeedsAdjacentStudyAndDemoSavesItLocally() = runTest(dispatcher) {
        val original = fixture.plan().let { plan ->
            plan.copy(current = plan.current.copy(schedules = plan.current.schedules +
                fixture.row(5, "2026/11/18/18/00", "2026/11/18/18/30")))
        }
        val o = open(PlanSnapshot(listOf(original)))
        saveFollowUpReport(o, StudyCheckInStatus.Partly, "40")
        val search = searchFollowUp(o)
        assertTrue(search.input.source.isDemo)
        val suggestion = search.suggestions.single()
        assertEquals("2026/11/18/18/30", WireDateTime.format(suggestion.start))
        assertEquals("2026/11/18/18/50", WireDateTime.format(suggestion.end))
        o.vm.selectFollowUpTime(0)
        assertTrue(o.vm.confirmRemainingFreeTime()); advanceUntilIdle()
        assertEquals(MoveSave.Saved, (o.vm.remainingStudy.value as RemainingStudyState.Success).save)
        assertEquals("2026/11/18/18/30", restored(o).plans.single().current.schedules.single { it.scheduleId == "study-6" }.startDate)
        assertTrue(o.batch.inputs.isEmpty())
        assertEquals(0, o.other.mutations)
        assertEquals(0, o.calendar.writes)
    }

    @Test fun changedCalendarDiscardsTheSelectionAndRequiresNewConfirmation() = runTest(dispatcher) {
        val o = open()
        saveFollowUpReport(o, StudyCheckInStatus.Missed)
        val search = searchFollowUp(o)
        val chosen = search.suggestions.first()
        o.calendar.outcome = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0,
            listOf(CalendarOccurrence("new event", null, chosen.start, chosen.end, false))))
        o.vm.selectFollowUpTime(0)
        assertTrue(o.vm.confirmRemainingFreeTime()); advanceUntilIdle()
        val updated = o.vm.remainingStudy.value as RemainingStudyState.CalendarSearch
        assertTrue(updated.changed)
        assertNull(updated.selected)
        assertFalse(chosen in updated.suggestions)
        assertTrue(o.batch.inputs.isEmpty())
        assertFalse(o.vm.confirmRemainingFreeTime())
        o.vm.selectFollowUpTime(0)
        assertTrue(o.vm.confirmRemainingFreeTime()); advanceUntilIdle()
        assertEquals(1, o.batch.inputs.size)
        assertEquals(3, o.calendar.reads)
    }

    @Test fun denialUnreadableCalendarAndNoSuitableTimeKeepTheReportAndPlan() = runTest(dispatcher) {
        for (phase in listOf(FreeTimePhase.PermissionDenied, FreeTimePhase.Unavailable, FreeTimePhase.NoTime)) {
            val o = open()
            saveFollowUpReport(o, StudyCheckInStatus.Partly, "40")
            val saved = restored(o)
            if (phase == FreeTimePhase.Unavailable) o.calendar.outcome = CalendarReadOutcome.Unavailable
            val search = searchFollowUp(o, granted = phase != FreeTimePhase.PermissionDenied)
            assertEquals(phase, search.phase)
            assertTrue(search.suggestions.isEmpty())
            assertFalse(o.vm.confirmRemainingFreeTime())
            assertTrue(o.vm.closeRemainingStudy())
            assertEquals(if (phase == FreeTimePhase.PermissionDenied) 0 else 1, o.calendar.reads)
            assertEquals(saved, restored(o))
            assertTrue(o.batch.inputs.isEmpty())
        }
    }

    @Test fun leavingAReadingFollowUpDiscardsItsLateResults() = runTest(dispatcher) {
        val o = open()
        saveFollowUpReport(o, StudyCheckInStatus.Missed)
        o.calendar.gate = CompletableDeferred()
        assertTrue(o.vm.startCheckInFollowUp())
        val search = o.vm.remainingStudy.value as RemainingStudyState.CalendarSearch
        o.vm.onRemainingFreeTimePermission(true, search.id)
        runCurrent()
        assertTrue(o.vm.closeRemainingStudy())
        saveFollowUpReport(o, StudyCheckInStatus.Partly, "40")
        assertTrue(o.vm.startCheckInFollowUp())
        o.calendar.gate!!.complete(Unit); advanceUntilIdle()
        val current = o.vm.remainingStudy.value as RemainingStudyState.CalendarSearch
        assertNotEquals(search.id, current.id)
        assertEquals(FreeTimePhase.RequestingPermission, current.phase)
        assertEquals(20L, current.input.work.totalMinutes)
        assertTrue(o.batch.inputs.isEmpty())
    }

    @Test fun lostFollowUpResponseRequiresReloadAndDoesNotAutomaticallyResend() = runTest(dispatcher) {
        val o = open()
        saveFollowUpReport(o, StudyCheckInStatus.Missed)
        searchFollowUp(o)
        o.batch.error = ApiError.Connection
        o.vm.selectFollowUpTime(0)
        assertTrue(o.vm.confirmRemainingFreeTime()); advanceUntilIdle()
        assertTrue(o.vm.closeRemainingStudy())
        assertEquals(PlanRecovery.ReloadRequired, restored(o).plans.first().recovery)
        assertFalse(o.vm.startRemainingStudy(1))
        assertTrue(o.vm.selectPlan(1))
        o.other.rows = a.current.schedules
        assertTrue(o.vm.reloadPlan()); advanceUntilIdle()
        assertEquals(1, o.other.reloads)
        assertEquals(1, o.batch.inputs.size)
        assertTrue(o.vm.startRemainingStudy(1))
    }

    @Test fun reviewCancelAndZeroWorkWriteNothingAndCallNothing() = runTest(dispatcher) {
        val o = open()
        assertTrue(o.vm.startRemainingStudy(2))
        val review = o.vm.remainingStudy.value as RemainingStudyState.Review
        assertEquals(b, review.input.source)
        assertTrue(o.vm.closeRemainingStudy())
        assertEquals(0, o.store.count)
        assertEquals(0, o.batch.inputs.size)
        assertEquals(0, o.calendar.reads)
        val done = a.copy(checkIns = a.current.schedules.associate { it.scheduleId to StudyCheckIn(StudyCheckInStatus.Done, 1) })
        val empty = open(PlanSnapshot(listOf(done)))
        assertTrue(empty.vm.startRemainingStudy(1))
        assertFalse(empty.vm.confirmRemainingStudy())
        assertEquals(0L, (empty.vm.remainingStudy.value as RemainingStudyState.Review).input.work.totalMinutes)
        assertEquals(0, empty.store.count)
    }

    @Test fun delayedPreSaveBlocksAllCompetingMutationsAndDispatchesOnce() = runTest(dispatcher) {
        val o = open(); val vm = o.vm
        assertTrue(vm.selectPlan(1)); vm.selectFailedSession("study-2")
        assertTrue(vm.startRemainingStudy(1))
        advanceUntilIdle()
        assertNotNull(vm.exportBlocks.value[1])
        assertFalse(vm.startCalendarExport(1)); assertFalse(vm.startRegeneration(1)); assertFalse(vm.startManualMove())
        assertFalse(vm.startCheckIn(2, "study-1")); assertFalse(vm.selectPlan(2)); assertFalse(vm.saveDraftAndExit())
        assertFalse(vm.keepCurrentConflict(1, "study-2")); assertFalse(vm.requestNoticeCheck()); assertFalse(vm.generate())
        val gate = CompletableDeferred<Unit>(); o.store.gate = gate
        assertTrue(vm.confirmRemainingStudy()); assertFalse(vm.confirmRemainingStudy())
        runCurrent()
        assertEquals(0, o.batch.inputs.size)
        assertEquals(PlanStorage.Saving, vm.storage.value)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, o.batch.inputs.size)
        assertEquals(0, o.calendar.reads); assertEquals(0, o.calendar.writes)
        assertEquals(MoveSave.Saved, (vm.remainingStudy.value as RemainingStudyState.Success).save)
        assertNull(vm.adjustment.value.failedScheduleId); assertNull(vm.adjustment.value.manual)
        assertEquals(AdjustmentState.Idle, vm.adjustment.value.state)
        val saved = restored(o)
        assertEquals(b, saved.plans[1]); assertEquals(draft, saved.creationDraft)
        assertEquals(a.checkIns, saved.plans[0].checkIns); assertEquals(a.keptConflicts, saved.plans[0].keptConflicts)
        assertEquals(a.current.schedules.first(), saved.plans[0].current.schedules.first { it.scheduleId == "study-1" })
        assertNull(saved.plans[0].checkInFor("study-61"))
    }

    @Test fun failedPreSaveRollsBackLocalRetryAndNeedsFreshConfirmation() = runTest(dispatcher) {
        val o = open(); val before = o.store.disk.committed!!.copyOf()
        o.store.fail += 1
        assertTrue(o.vm.startRemainingStudy(1)); assertTrue(o.vm.confirmRemainingStudy()); advanceUntilIdle()
        assertEquals(0, o.batch.inputs.size); assertArrayEquals(before, o.store.disk.committed)
        assertEquals(PlanStorage.SaveFailure, o.vm.storage.value)
        o.vm.retrySave(); advanceUntilIdle()
        assertEquals(0, o.batch.inputs.size); assertEquals(PlanRecovery.Ready, restored(o).plans[0].recovery)
        assertFalse(o.vm.confirmRemainingStudy())
        assertTrue(o.vm.closeRemainingStudy()); assertTrue(o.vm.startRemainingStudy(1)); assertTrue(o.vm.confirmRemainingStudy())
        advanceUntilIdle(); assertEquals(1, o.batch.inputs.size)
    }

    @Test fun outcomeFailureRetriesOnlyCapturedLocalSnapshotAndRestoresEverything() = runTest(dispatcher) {
        val o = open(); o.store.fail += 2
        assertTrue(o.vm.startRemainingStudy(1)); assertTrue(o.vm.confirmRemainingStudy()); advanceUntilIdle()
        assertEquals(MoveSave.Unsaved, (o.vm.remainingStudy.value as RemainingStudyState.Success).save)
        assertEquals(PlanRecovery.ReloadRequired, restored(o).plans[0].recovery)
        assertFalse(o.vm.startRemainingStudy(2)); assertFalse(o.vm.startCalendarExport(2))
        o.vm.retrySave(); advanceUntilIdle()
        assertEquals(1, o.batch.inputs.size)
        assertEquals(MoveSave.Saved, (o.vm.remainingStudy.value as RemainingStudyState.Success).save)
        val snapshot = restored(o); assertEquals(b, snapshot.plans[1]); assertEquals(draft, snapshot.creationDraft)
        assertEquals(a.checkIns, snapshot.plans[0].checkIns); assertEquals(a.keptConflicts, snapshot.plans[0].keptConflicts)
        val fresh = open(disk = o.store.disk)
        assertEquals(snapshot.plans, fresh.vm.plans.value); assertNull(fresh.vm.remainingStudy.value)
        assertEquals(0, fresh.batch.inputs.size)
    }

    @Test fun closedUnsavedReportOnBBlocksAUntilLocalSaveAndFreshReview() = runTest(dispatcher) {
        val o = open(); val vm = o.vm
        assertTrue(vm.startCheckIn(2, "study-1")); vm.chooseCheckInStatus(StudyCheckInStatus.Partly); vm.setCheckInMinutes("12")
        o.store.fail += 1; assertTrue(vm.saveCheckIn()); advanceUntilIdle(); vm.closeCheckIn()
        assertFalse(vm.startRemainingStudy(1)); assertEquals(0, o.batch.inputs.size)
        vm.retrySave(); advanceUntilIdle(); assertTrue(vm.startRemainingStudy(1))
        assertTrue(vm.confirmRemainingStudy()); advanceUntilIdle()
        assertEquals(12, restored(o).plans[1].checkInFor("study-1")!!.actualMinutes)
    }

    @Test fun savedUnrelatedReportFeedbackDoesNotSettleWithRemainingOutcomeRetry() = runTest(dispatcher) {
        val o = open(); val vm = o.vm
        assertTrue(vm.startCheckIn(2, "study-1")); vm.chooseCheckInStatus(StudyCheckInStatus.Done); assertTrue(vm.saveCheckIn()); advanceUntilIdle()
        val report = vm.checkIn.value
        o.store.fail += o.store.count + 2
        assertTrue(vm.startRemainingStudy(1)); assertTrue(vm.confirmRemainingStudy()); advanceUntilIdle()
        assertEquals(report, vm.checkIn.value)
        vm.retrySave(); advanceUntilIdle(); assertEquals(report, vm.checkIn.value)
        assertEquals(CheckInSave.Saved, vm.checkIn.value!!.save)
    }

    @Test fun foregroundNoticeQueryAndReviewHoldTheirOwnSources() = runTest(dispatcher) {
        val future = a.copy(current = a.current.copy(schedules = a.current.schedules +
            fixture.row(99, "2026/11/20/18/00", "2026/11/20/19/00")))
        val o = open(PlanSnapshot(listOf(future, b), creationDraft = draft)); val vm = o.vm
        o.calendar.gate = CompletableDeferred()
        vm.enterForeground(true); runCurrent()
        assertFalse(vm.startRemainingStudy(1))
        o.calendar.gate!!.complete(Unit); advanceUntilIdle()
        val reads = o.calendar.reads
        assertTrue(vm.startRemainingStudy(1)); vm.leaveForeground(); vm.enterForeground(true); advanceUntilIdle()
        assertEquals(reads, o.calendar.reads)
        assertTrue(vm.closeRemainingStudy()); advanceUntilIdle(); assertTrue(o.calendar.reads > reads)
    }

    @Test fun calendarPermissionExportAndManualReviewBlockEntry() = runTest(dispatcher) {
        val o = open(); val vm = o.vm
        assertTrue(vm.requestNoticeCheck()); assertFalse(vm.startRemainingStudy(1)); vm.onNoticePermission(false)
        assertTrue(vm.startCalendarExport(1)); assertFalse(vm.startRemainingStudy(1)); vm.closeCalendarExport()
        assertTrue(vm.selectPlan(1)); vm.selectFailedSession("study-2"); assertTrue(vm.startManualMove())
        assertFalse(vm.startRemainingStudy(1)); vm.cancelManualMove(); assertTrue(vm.startRemainingStudy(1))
    }

    @Test fun closedUnsavedKeepOnBBlocksAAndRetryDoesNotResumeIntent() = runTest(dispatcher) {
        val row = fixture.row(99, "2026/11/20/18/00", "2026/11/20/19/00")
        val futureB = b.copy(current = b.current.copy(schedules = b.current.schedules + row))
        val o = open(PlanSnapshot(listOf(a, futureB), creationDraft = draft))
        o.calendar.outcome = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0, listOf(
            CalendarOccurrence("occurrence", null, WireDateTime.parseOrNull(row.startDate)!!, WireDateTime.parseOrNull(row.endDate)!!, false))))
        o.vm.enterForeground(true); advanceUntilIdle()
        o.store.fail += 1
        assertTrue(o.vm.keepCurrentConflict(2, row.scheduleId)); advanceUntilIdle()
        assertEquals(MoveSave.Unsaved, o.vm.keepFeedback.value!!.save)
        o.vm.closeKeepFeedback()
        assertFalse(o.vm.startRemainingStudy(1)); assertEquals(0, o.batch.inputs.size)
        o.vm.retrySave(); advanceUntilIdle()
        assertEquals(0, o.batch.inputs.size); assertNull(o.vm.remainingStudy.value)
        assertTrue(restored(o).plans[1].keptConflicts.getValue(row.scheduleId).isNotEmpty())
        assertTrue(o.vm.startRemainingStudy(1))
    }

    @Test fun uncertainFailureAndRestartRequireExplicitReloadWithNoReplay() = runTest(dispatcher) {
        val o = open(); o.batch.gate = CompletableDeferred()
        assertTrue(o.vm.startRemainingStudy(1)); assertTrue(o.vm.confirmRemainingStudy()); runCurrent()
        assertEquals(1, o.batch.inputs.size)
        val marker = restored(o); assertEquals(PlanRecovery.ReloadRequired, marker.plans[0].recovery)
        assertNull(marker.plans[0].recoveryScheduleId)
        o.vm.viewModelScope.cancel()
        val fresh = open(disk = o.store.disk)
        assertFalse(fresh.vm.startRemainingStudy(1)); assertEquals(0, fresh.batch.inputs.size)
        fresh.other.rows = RemainingStudy.place(a, RemainingStudy.calculate(a), fixture.now.plusMinutes(1), fixture.now)!!
        assertTrue(fresh.vm.selectPlan(1)); assertTrue(fresh.vm.reloadPlan()); advanceUntilIdle()
        assertEquals(1, fresh.other.reloads); assertEquals(0, fresh.other.mutations)
        assertEquals(a.checkIns, restored(fresh).plans[0].checkIns); assertEquals(a.keptConflicts, restored(fresh).plans[0].keptConflicts)
        assertTrue(fresh.vm.startRemainingStudy(1))
        assertEquals(160L, (fresh.vm.remainingStudy.value as RemainingStudyState.Review).input.work.totalMinutes)
    }

    @Test fun invalidSuccessAndStorageFailureRetainSourceAndGenericReloadGate() = runTest(dispatcher) {
        for (error in listOf(ApiError.Connection, ApiError.PlanConflict, ApiError.ServerFailure(500))) {
            val o = open(); o.batch.error = error
            assertTrue(o.vm.startRemainingStudy(1)); assertTrue(o.vm.confirmRemainingStudy()); advanceUntilIdle()
            assertEquals(a.current, o.vm.plans.value[0].current)
            assertEquals(PlanRecovery.ReloadRequired, o.vm.plans.value[0].recovery)
            assertFalse(o.vm.confirmRemainingStudy()); assertEquals(1, o.batch.inputs.size)
        }
        val o = open(); o.batch.alter = { it.copy(schedules = it.schedules.drop(1)) }
        assertTrue(o.vm.startRemainingStudy(1)); assertTrue(o.vm.confirmRemainingStudy()); advanceUntilIdle()
        assertEquals(PlanRecovery.ReloadRequired, o.vm.plans.value[0].recovery)
        assertEquals(a.current, o.vm.plans.value[0].current)
    }

    @Test fun definiteAdmissionRejectionKeepsTheSourceWithoutAReloadGateOrReplay() = runTest(dispatcher) {
        for (error in listOf(ApiError.RateLimited, ApiError.DeviceTokenRejected)) {
            val o = open(); o.batch.error = error
            assertTrue(o.vm.startRemainingStudy(1)); assertTrue(o.vm.confirmRemainingStudy()); advanceUntilIdle()
            val failure = o.vm.remainingStudy.value as RemainingStudyState.Failure
            assertEquals(error, failure.error)
            assertEquals(error.userMessage(), remainingFailureMessage(failure.error))
            assertEquals(a.current, o.vm.plans.value[0].current)
            assertEquals(PlanRecovery.Ready, o.vm.plans.value[0].recovery)
            assertEquals(PlanRecovery.Ready, restored(o).plans[0].recovery)
            assertFalse(o.vm.confirmRemainingStudy())
            assertEquals(1, o.batch.inputs.size); assertEquals(0, o.other.reloads)
        }
    }

    @Test fun definiteValidationFailureAllowsOnlyFreshReviewAndFailedReloadSaveStaysBlocked() = runTest(dispatcher) {
        val o = open(); o.batch.error = ApiError.InvalidInput("mode must be automatic or manual")
        assertTrue(o.vm.startRemainingStudy(1)); assertTrue(o.vm.confirmRemainingStudy()); advanceUntilIdle()
        assertEquals(PlanRecovery.Ready, restored(o).plans[0].recovery)
        assertFalse(o.vm.confirmRemainingStudy()); assertTrue(o.vm.closeRemainingStudy()); assertTrue(o.vm.startRemainingStudy(1))
        o.vm.closeRemainingStudy()
        val pending = open(PlanSnapshot(listOf(a.copy(recovery = PlanRecovery.ReloadRequired), b), creationDraft = draft))
        pending.other.rows = a.current.schedules; pending.store.fail += 1
        assertTrue(pending.vm.selectPlan(1)); assertTrue(pending.vm.reloadPlan()); advanceUntilIdle()
        assertFalse(pending.vm.startRemainingStudy(1)); assertEquals(PlanStorage.SaveFailure, pending.vm.storage.value)
        pending.vm.retrySave(); advanceUntilIdle(); assertTrue(pending.vm.startRemainingStudy(1))
        assertEquals(1, pending.other.reloads)
    }

    @Test fun everySupportedVersionLoadsWithoutWritingThenExplicitlySavesTheWholeCollection() = runTest(dispatcher) {
        for (version in 1..PlanSnapshotCodec.VERSION) {
            val original = a.original.copy(weeklyAvailability = a.original.weeklyAvailability.mapValues { listOf(TimeWindow("18:00", "22:00")) })
            val plans = listOf(a, b).map { it.copy(original = original,
                checkIns = if (version >= 3) it.checkIns else emptyMap(),
                keptConflicts = if (version >= 5) it.keptConflicts else emptyMap()) }
            val snapshot = PlanSnapshot(plans, creationDraft = draft.takeIf { version >= 4 })
            val root = ApiJson.parseToJsonElement(PlanSnapshotCodec.encode(snapshot, scope)).jsonObject.toMutableMap()
            root["version"] = JsonPrimitive(version)
            if (version < 6) root.remove("next_plan_ordinal")
            root["entries"] = JsonArray(root.getValue("entries").jsonArray.map { element ->
                JsonObject(element.jsonObject.toMutableMap().apply {
                    if (version < 3) remove("check_ins")
                    if (version < 5) remove("kept_conflicts")
                })
            })
            val bytes = JsonObject(root).toString().encodeToByteArray()
            val o = open(disk = MemoryPlanBytes(bytes))
            assertArrayEquals(bytes, o.store.disk.committed); assertEquals(0, o.store.count)
            assertTrue(o.vm.startRemainingStudy(1)); assertTrue(o.vm.confirmRemainingStudy()); advanceUntilIdle()
            assertEquals(PlanSnapshotCodec.VERSION, ApiJson.parseToJsonElement(o.store.disk.committed!!.decodeToString()).jsonObject.getValue("version").jsonPrimitive.int)
            assertEquals(plans[1], restored(o).plans[1]); assertEquals(snapshot.creationDraft, restored(o).creationDraft)
            assertEquals(plans[0].checkIns, restored(o).plans[0].checkIns); assertEquals(plans[0].keptConflicts, restored(o).plans[0].keptConflicts)
        }
    }
}
