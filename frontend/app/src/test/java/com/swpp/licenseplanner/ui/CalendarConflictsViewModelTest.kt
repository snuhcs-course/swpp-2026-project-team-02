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

/** Existing behavior tests explicitly complete a successful empty calendar check. */
internal object ClearCalendarForTest : CalendarReadRepository {
    override suspend fun read(window: CalendarReadWindow) = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0))
}

internal fun PlanFormViewModel.generateAfterCalendarCheck(): Boolean = generate().also {
    if (it) onConflictPermission(true)
}

internal fun PlanFormViewModel.retryAfterCalendarCheck() {
    retryGeneration()
    onConflictPermission(true)
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarConflictsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val day = LocalDate.of(2031, 3, 3)
    private val request = (RequestMapper.map(TestFixtures.completeState(start = day, exam = day.plusWeeks(6))) as MappingResult.Ready).request
    private val rows = listOf(ScheduleRow("study-1", "2031/03/03/18/00", "2031/03/03/19/00", "원래 토픽"))
    private fun plan(ordinal: Int) = SessionPlan.register(ordinal, request, StudyPlanResponse(rows, scheduleCsvFile = "old-$ordinal"), false)
        .copy(checkIns = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Done, 20)))
    private val draft = CreationDraft(TestFixtures.completeState(start = day, exam = day.plusWeeks(6)).copy(level = CurrentLevel.ADVANCED), CreationStep.QUESTIONS, 2)
    private val source = PlanSnapshot(listOf(plan(1), plan(2)), creationDraft = draft)

    private class Store(var snapshot: PlanSnapshot) : LocalPlanStore {
        val attempts = mutableListOf<PlanSnapshot>()
        var fail = false
        val failingAttempts = mutableSetOf<Int>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load() = PlanStoreLoad.Loaded(snapshot)
        override suspend fun save(snapshot: PlanSnapshot): Boolean {
            attempts += snapshot
            gate?.await()
            if (fail || attempts.size in failingAttempts) return false
            this.snapshot = snapshot
            return true
        }
    }
    private class Reader : CalendarReadRepository {
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        var outcome: CalendarReadOutcome = CalendarReadOutcome.Loaded(CalendarBusySnapshot(emptyList(), 0, 0))
        val outcomes = ArrayDeque<CalendarReadOutcome>()
        val windows = mutableListOf<CalendarReadWindow>()
        override suspend fun read(window: CalendarReadWindow): CalendarReadOutcome {
            calls++
            windows += window
            val captured = outcomes.removeFirstOrNull() ?: outcome
            gate?.await()
            return captured
        }
    }
    private class Planning : PlanningRepository {
        val requests = mutableListOf<StudyPlanRequest>()
        var fail = false
        var response: StudyPlanResponse? = null
        override suspend fun generate(request: StudyPlanRequest): PlanOutcome {
            requests += request
            return if (fail) PlanOutcome.Failure(ApiError.Connection) else PlanOutcome.Success(
                response ?: StudyPlanResponse(listOf(ScheduleRow("study-1", "2031/03/04/18/00", "2031/03/04/19/00", "새 토픽")), scheduleCsvFile = "new-${requests.size}"), false)
        }
    }
    private class Writer : CalendarExportRepository {
        val destination = CalendarDestination(90, "테스트 캘린더")
        var calls = 0
        override suspend fun calendars() = CalendarListOutcome.Loaded(listOf(destination))
        override suspend fun export(destination: CalendarDestination, events: List<CalendarEvent>): CalendarWriteOutcome {
            calls++
            return CalendarWriteOutcome.Complete(events.size)
        }
    }
    private val adjustment = object : AdjustmentRepository {
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
            val changed = input.schedules.map { if (it.scheduleId == input.scheduleId) it.copy(startDate = "2031/03/05/18/00", endDate = "2031/03/05/19/00") else it }
            return AdjustmentOutcome.Success(StudyPlanResponse(changed))
        }
        override suspend fun reload(scheduleCsvFile: String?) = ReloadOutcome.Failure(ApiError.Connection)
    }
    private var questionCalls = 0
    private fun model(store: LocalPlanStore, reader: CalendarReadRepository, planning: Planning = Planning(), writer: CalendarExportRepository = Writer()) = PlanFormViewModel(
        object : QuestionRepository {
            override suspend fun load(certificationId: String): QuestionOutcome {
                questionCalls++
                return QuestionOutcome.Loaded(TestFixtures.publicQuestions())
            }
        }, planning, adjustment, today = day, planStore = store, calendarReadRepository = reader,
        calendarExportRepository = writer, currentDate = { day.plusYears(1) },
    )
    private fun busy(key: String = "one", second: Int = 0): CalendarReadOutcome {
        val row = ScheduleRow("7", "2031/03/03/18/30", "2031/03/03/19/30", "외부 일정")
        return CalendarReadOutcome.Loaded(CalendarBusySnapshot(listOf(row), 0, 0, listOf(
            CalendarOccurrence(key, null, day.atTime(18, 30, second), day.atTime(19, 30), false),
        )))
    }
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    private fun TestScope.creation(vm: PlanFormViewModel) {
        advanceUntilIdle()
        vm.selectCertification(CERTIFICATION_ID)
        vm.setExamDate(day.plusWeeks(6))
        vm.setLevel(CurrentLevel.INTERMEDIATE)
        vm.loadQuestions()
        advanceUntilIdle()
        TestFixtures.publicQuestions().forEach { vm.answer(it.id, it.correctAnswer) }
        vm.setDayEnabled(Weekday.MONDAY, true)
    }
    private fun TestScope.replace(vm: PlanFormViewModel) {
        assertTrue(vm.regenerationBlockReason(1), vm.startRegeneration(1))
        assertTrue(vm.confirmRegeneration())
        vm.onConflictPermission(true)
        advanceUntilIdle()
    }

    @Test fun creationReviewEditAndFinalChangedOccurrenceNeverSendStaleConsent() = runTest(dispatcher) {
        val reader = Reader().apply { outcome = busy() }
        val planning = Planning()
        val vm = model(Store(PlanSnapshot.EMPTY), reader, planning)
        creation(vm)
        val form = vm.form.value
        assertTrue(vm.generate())
        assertEquals(0, reader.calls)
        vm.onConflictPermission(true)
        advanceUntilIdle()
        assertEquals(ConflictPhase.Review, vm.calendarConflict.value!!.phase)
        assertTrue(planning.requests.isEmpty())
        vm.showConflictDetails()
        assertTrue(vm.calendarConflict.value!!.conflicts.matches.all { it.target.scheduleId == null })
        assertFalse(vm.continueCalendarConflict())
        vm.showConflictSummary()
        reader.outcome = busy("changed", 1)
        assertTrue(vm.continueCalendarConflict())
        advanceUntilIdle()
        assertTrue(planning.requests.isEmpty())
        assertEquals(form, vm.form.value)
        assertTrue(vm.continueCalendarConflict())
        assertFalse(vm.continueCalendarConflict())
        advanceUntilIdle()
        assertEquals(1, planning.requests.size)
        assertEquals((reader.outcome as CalendarReadOutcome.Loaded).snapshot.schedules, planning.requests.single().busySchedules)
        assertEquals(form.questions.map { it.id }, planning.requests.single().assessmentResults.results.map { it.problemId })
    }

    @Test fun changedInputAndDelayedReadDiscardTheWholeEarlierReview() = runTest(dispatcher) {
        val reader = Reader().apply { outcome = busy(); gate = CompletableDeferred() }
        val planning = Planning()
        val vm = model(Store(PlanSnapshot.EMPTY), reader, planning)
        creation(vm)
        assertTrue(vm.generate())
        vm.onConflictPermission(true)
        runCurrent()
        vm.setExamDate(day.plusWeeks(8))
        reader.gate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.calendarConflict.value)
        assertTrue(planning.requests.isEmpty())
        reader.gate = null
        assertTrue(vm.generate())
        vm.onConflictPermission(true)
        advanceUntilIdle()
        vm.cancelCalendarConflict()
        assertTrue(planning.requests.isEmpty())
        assertEquals(day.plusWeeks(8), vm.form.value.examDate)
    }

    @Test fun unavailableGenerationRetainsImportedRowsAndNetworkRetryChecksAgain() = runTest(dispatcher) {
        val reader = Reader().apply { outcome = busy() }
        val planning = Planning().apply { fail = true }
        val vm = model(Store(PlanSnapshot.EMPTY), reader, planning)
        creation(vm)
        assertTrue(vm.startCalendarRead())
        vm.onCalendarReadPermission(true)
        advanceUntilIdle()
        val retained = vm.form.value.busySchedules
        reader.outcome = CalendarReadOutcome.Unavailable
        assertTrue(vm.generate())
        vm.onConflictPermission(true)
        advanceUntilIdle()
        assertEquals(ConflictPhase.Unchecked, vm.calendarConflict.value!!.phase)
        assertTrue(planning.requests.isEmpty())
        assertTrue(vm.continueCalendarConflict(unchecked = true))
        advanceUntilIdle()
        assertEquals(retained, planning.requests.single().busySchedules)
        planning.fail = false
        reader.outcome = busy("new")
        vm.retryGeneration()
        vm.onConflictPermission(true)
        advanceUntilIdle()
        assertEquals(1, planning.requests.size)
        assertTrue(vm.continueCalendarConflict())
        advanceUntilIdle()
        assertEquals(2, planning.requests.size)
    }

    @Test fun deniedGenerationRequiresExplicitUncheckedAndFinalReadCannotReuseDenialForFreshData() = runTest(dispatcher) {
        val reader = Reader().apply { outcome = CalendarReadOutcome.PermissionMissing }
        val planning = Planning()
        val vm = model(Store(PlanSnapshot.EMPTY), reader, planning)
        creation(vm)
        vm.generate()
        assertTrue(vm.conflictPermissionPrompted())
        assertFalse(vm.conflictPermissionPrompted())
        vm.onConflictPermission(false)
        assertEquals(0, reader.calls)
        assertEquals(ConflictPhase.Unchecked, vm.calendarConflict.value!!.phase)
        vm.continueCalendarConflict(true)
        advanceUntilIdle()
        assertEquals(1, planning.requests.size)
        assertTrue(planning.requests.single().busySchedules.isEmpty())
    }

    @Test fun replacementRetainsOldReportsUntilExactCandidateCommitsAndNeverConsumesSeparateDraft() = runTest(dispatcher) {
        val store = Store(source)
        val reader = Reader()
        val planning = Planning()
        val vm = model(store, reader, planning)
        advanceUntilIdle()
        store.fail = true
        replace(vm)
        assertEquals(source.plans, vm.plans.value)
        assertEquals(source, store.snapshot)
        assertEquals(ConflictPhase.Unsaved, vm.calendarConflict.value!!.phase)
        assertEquals(1, planning.requests.size)
        assertEquals(request.copy(busySchedules = emptyList()), planning.requests.single())
        val candidate = store.attempts.single()
        assertEquals(source.plans[1], candidate.plans[1])
        assertEquals(draft, candidate.creationDraft)
        assertTrue(candidate.plans[0].checkIns.isEmpty())
        store.gate = CompletableDeferred()
        vm.retrySave()
        vm.retrySave()
        runCurrent()
        assertEquals(ConflictPhase.Saving, vm.calendarConflict.value!!.phase)
        assertEquals(2, store.attempts.size)
        store.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(source.plans, vm.plans.value)
        store.gate = null
        store.fail = false
        vm.retrySave()
        advanceUntilIdle()
        assertTrue(store.attempts.all { it == candidate })
        assertEquals(candidate, store.snapshot)
        assertEquals(candidate.plans, vm.plans.value)
        assertEquals(1, planning.requests.size)
        assertEquals(2, reader.calls)
        assertEquals(0, questionCalls)
        val restored = model(store, Reader())
        advanceUntilIdle()
        assertEquals(candidate.plans, restored.plans.value)
        assertEquals(draft, restored.committedDraft.value)
        assertNull(restored.calendarConflict.value)
    }

    @Test fun failedClosedReportOnAnotherPlanBlocksEveryRegenerationEntryUntilOriginalRetrySucceeds() = runTest(dispatcher) {
        val store = Store(source)
        val reader = Reader()
        val planning = Planning()
        val vm = model(store, reader, planning)
        advanceUntilIdle()
        assertTrue(vm.startCheckIn(2, "study-1"))
        vm.chooseCheckInStatus(StudyCheckInStatus.Missed)
        store.fail = true
        assertTrue(vm.saveCheckIn())
        assertFalse(vm.startRegeneration(1))
        advanceUntilIdle()
        val pending = store.attempts.single()
        val editor = vm.checkIn.value
        assertEquals(CheckInSave.Unsaved, editor!!.save)
        assertFalse(vm.startRegeneration(1))
        assertFalse(vm.confirmRegeneration())
        vm.retryGeneration()
        assertEquals(editor, vm.checkIn.value)
        vm.closeCheckIn()
        assertFalse(vm.startRegeneration(1))
        vm.retrySave()
        advanceUntilIdle()
        assertEquals(pending, store.attempts.last())
        assertFalse(vm.startRegeneration(1))
        assertTrue(planning.requests.isEmpty())
        assertEquals(0, reader.calls)
        store.fail = false
        vm.retrySave()
        advanceUntilIdle()
        assertNull(vm.checkIn.value)
        assertNull(vm.calendarConflict.value)
        assertEquals(pending, store.snapshot)
        replace(vm)
        assertEquals(pending.plans[1], store.snapshot.plans[1])
        assertEquals(draft, store.snapshot.creationDraft)
    }

    @Test fun replacementRetriesLeaveUnrelatedSavedMoveAndReportFeedbackUntouchedEvenWhenCleared() = runTest(dispatcher) {
        val store = Store(source)
        val reader = Reader()
        val planning = Planning()
        val vm = model(store, reader, planning)
        advanceUntilIdle()
        assertTrue(vm.selectPlan(2))
        vm.selectFailedSession("study-1")
        assertTrue(vm.submitAdjustment())
        advanceUntilIdle()
        val feedback = vm.adjustment.value
        assertEquals(MoveSave.Saved, (feedback.state as AdjustmentState.Success).save)
        assertTrue(vm.startCheckIn(2, "study-1"))
        vm.chooseCheckInStatus(StudyCheckInStatus.Partly)
        vm.setCheckInMinutes("12")
        assertTrue(vm.saveCheckIn())
        advanceUntilIdle()
        val report = vm.checkIn.value
        assertEquals(CheckInSave.Saved, report!!.save)
        store.fail = true
        replace(vm)
        assertEquals(feedback, vm.adjustment.value)
        assertEquals(report, vm.checkIn.value)
        store.gate = CompletableDeferred()
        vm.retrySave()
        runCurrent()
        assertEquals(feedback, vm.adjustment.value)
        assertEquals(report, vm.checkIn.value)
        vm.closeCheckIn()
        vm.selectFailedSession("study-1")
        store.gate!!.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.checkIn.value)
        assertEquals(feedback, vm.adjustment.value)
        vm.cancelCalendarConflict()
        vm.selectFailedSession("study-1")
        store.gate = null
        store.fail = false
        vm.retrySave()
        advanceUntilIdle()
        assertNull(vm.calendarConflict.value)
        assertNull(vm.checkIn.value)
        assertEquals(AdjustmentState.Idle, vm.adjustment.value.state)
        assertEquals(draft, store.snapshot.creationDraft)
        assertEquals(1, planning.requests.size)
    }

    @Test fun exportOverrideNeedsFinalConfirmationAndChangedSameCountForcesFreshReview() = runTest(dispatcher) {
        val reader = Reader().apply { outcome = busy() }
        val planning = Planning()
        val writer = Writer()
        val vm = model(Store(source), reader, planning, writer)
        advanceUntilIdle()
        assertTrue(vm.startCalendarExport(1))
        vm.onCalendarPermission(true)
        advanceUntilIdle()
        vm.chooseCalendar(writer.destination)
        vm.reviewCalendarExport()
        advanceUntilIdle()
        assertEquals(ConflictKind.Export, vm.calendarConflict.value!!.kind)
        vm.showConflictDetails()
        assertEquals("study-1", vm.calendarConflict.value!!.conflicts.matches.single().target.scheduleId)
        vm.showConflictSummary()
        vm.continueCalendarConflict()
        assertTrue(vm.calendarExport.value!!.phase is CalendarExportPhase.Confirming)
        assertEquals(0, writer.calls)
        reader.outcome = busy("edited", 1)
        assertTrue(vm.confirmCalendarExport())
        assertFalse(vm.confirmCalendarExport())
        advanceUntilIdle()
        assertEquals(0, writer.calls)
        assertEquals(ConflictPhase.Review, vm.calendarConflict.value!!.phase)
        vm.continueCalendarConflict()
        assertTrue(vm.confirmCalendarExport())
        advanceUntilIdle()
        assertEquals(1, writer.calls)
        assertTrue(planning.requests.isEmpty())
        assertEquals(CalendarReadWindow(day, day.plusDays(1)), reader.windows.first())
    }

    @Test fun exportRegenerationLeavesCopyAndCancellationKeepsAllData() = runTest(dispatcher) {
        val reader = Reader().apply { outcome = busy() }
        val planning = Planning()
        val writer = Writer()
        val store = Store(source)
        val vm = model(store, reader, planning, writer)
        advanceUntilIdle()
        vm.startCalendarExport(1)
        vm.onCalendarPermission(true)
        advanceUntilIdle()
        vm.chooseCalendar(writer.destination)
        vm.reviewCalendarExport()
        advanceUntilIdle()
        assertTrue(vm.regenerationBlockReason(1), vm.startRegeneration(1))
        assertNull(vm.calendarExport.value)
        assertEquals(ConflictPhase.ReplacementConsent, vm.calendarConflict.value!!.phase)
        vm.cancelCalendarConflict()
        assertEquals(source, store.snapshot)
        assertTrue(planning.requests.isEmpty())
        assertEquals(0, writer.calls)
    }

    @Test fun failedMoveOnAnotherPlanRetainsItsExactRetryEvenAfterTheResultCloses() = runTest(dispatcher) {
        val store = Store(source)
        val reader = Reader()
        val planning = Planning()
        val vm = model(store, reader, planning)
        advanceUntilIdle()
        vm.selectPlan(2)
        vm.selectFailedSession("study-1")
        // The preflight succeeds. Only the outcome and its first retry fail.
        store.failingAttempts += setOf(2, 3)
        vm.submitAdjustment()
        advanceUntilIdle()
        assertEquals(MoveSave.Unsaved, (vm.adjustment.value.state as AdjustmentState.Success).save)
        val candidate = store.attempts.last()
        assertFalse(vm.startRegeneration(1))
        vm.selectFailedSession("study-1")
        assertEquals(AdjustmentState.Idle, vm.adjustment.value.state)
        assertFalse(vm.startRegeneration(1))
        vm.retrySave()
        advanceUntilIdle()
        assertEquals(candidate, store.attempts.last())
        assertEquals(AdjustmentState.Idle, vm.adjustment.value.state)
        assertFalse(vm.startRegeneration(1))
        store.fail = false
        vm.retrySave()
        advanceUntilIdle()
        assertEquals(AdjustmentState.Idle, vm.adjustment.value.state)
        assertEquals(candidate, store.snapshot)
        assertEquals(0, reader.calls)
        replace(vm)
        assertEquals(candidate.plans[1], store.snapshot.plans[1])
        assertEquals(draft, store.snapshot.creationDraft)
        assertEquals(1, planning.requests.size)
    }

    @Test fun transitionalReadyStorageCannotBypassAnUnresolvedParticipantOrPendingSnapshot() = runTest(dispatcher) {
        val store = Store(source)
        val reader = Reader()
        val vm = model(store, reader)
        advanceUntilIdle()
        vm.startCheckIn(2, "study-1")
        vm.chooseCheckInStatus(StudyCheckInStatus.Missed)
        store.fail = true
        vm.saveCheckIn()
        advanceUntilIdle()
        val field = PlanFormViewModel::class.java.getDeclaredField("_storage").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val storage = field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<PlanStorage>
        storage.value = PlanStorage.Ready
        assertFalse(vm.startRegeneration(1))
        vm.closeCheckIn()
        assertFalse(vm.startRegeneration(1))
        assertEquals(0, reader.calls)
        assertEquals(1, store.attempts.size)
    }

    @Test fun unavailableExportNeedsExplicitUncheckedAndPermissionFailureCannotOverride() = runTest(dispatcher) {
        val reader = Reader().apply { outcome = CalendarReadOutcome.PermissionMissing }
        val writer = Writer()
        val vm = model(Store(source), reader, writer = writer)
        advanceUntilIdle()
        vm.startCalendarExport(1)
        vm.onCalendarPermission(true)
        advanceUntilIdle()
        vm.chooseCalendar(writer.destination)
        vm.reviewCalendarExport()
        advanceUntilIdle()
        assertEquals(ConflictPhase.Unchecked, vm.calendarConflict.value!!.phase)
        assertFalse(vm.continueCalendarConflict(true))
        assertEquals(0, writer.calls)
        reader.outcome = CalendarReadOutcome.Unavailable
        vm.retryCalendarConflict()
        vm.onConflictPermission(true)
        advanceUntilIdle()
        assertTrue(vm.continueCalendarConflict(true))
        assertEquals(0, writer.calls)
        assertTrue(vm.confirmCalendarExport())
        advanceUntilIdle()
        assertEquals(1, writer.calls)
    }

    @Test fun failedReplacementKeepsActualBytesUntilRetryAndFreshCodecRestore() = runTest(dispatcher) {
        val disk = MemoryPlanBytes()
        val bytes = BytesPlanStore(disk, PlanStorageScope.of(false, "http://test.invalid/api/v1"), dispatcher)
        assertTrue(bytes.load() is PlanStoreLoad.Loaded)
        assertTrue(bytes.save(source))
        val old = disk.committed!!.copyOf()
        val reader = Reader()
        val planning = Planning()
        val vm = model(bytes, reader, planning)
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, vm.storage.value)
        assertEquals(source.plans, vm.plans.value)
        disk.failWrites = true
        replace(vm)
        assertArrayEquals(old, disk.committed)
        assertEquals(source.plans, vm.plans.value)
        disk.failWrites = false
        vm.retrySave()
        advanceUntilIdle()
        val freshStore = BytesPlanStore(MemoryPlanBytes(disk.committed), PlanStorageScope.of(false, "http://test.invalid/api/v1"), dispatcher)
        val restored = (freshStore.load() as PlanStoreLoad.Loaded).snapshot
        assertEquals(vm.plans.value, restored.plans)
        assertEquals(source.plans[1], restored.plans[1])
        assertEquals(draft, restored.creationDraft)
        assertTrue(restored.plans[0].checkIns.isEmpty())
        assertEquals(1, planning.requests.size)
    }

    @Test fun aLatePermissionAnswerCannotAuthorizeANewerActionAndWindowEditsInvalidateReview() = runTest(dispatcher) {
        val reader = Reader().apply { outcome = busy() }
        val planning = Planning()
        val vm = model(Store(PlanSnapshot.EMPTY), reader, planning)
        creation(vm)
        vm.generate()
        val oldId = vm.calendarConflict.value!!.id
        vm.conflictPermissionPrompted()
        vm.cancelCalendarConflict()
        vm.generate()
        val newId = vm.calendarConflict.value!!.id
        assertNotEquals(oldId, newId)
        vm.onConflictPermission(true, oldId)
        advanceUntilIdle()
        assertEquals(0, reader.calls)
        assertEquals(ConflictPhase.RequestingPermission, vm.calendarConflict.value!!.phase)
        vm.onConflictPermission(true, newId)
        advanceUntilIdle()
        vm.stepDuration(Weekday.MONDAY, 1)
        assertNull(vm.calendarConflict.value)
        assertFalse(vm.continueCalendarConflict())
        assertTrue(planning.requests.isEmpty())
    }

    @Test fun invalidReplacementResponsePreservesOldBytesReportsAndDraftWithoutASave() = runTest(dispatcher) {
        for (response in listOf(
            StudyPlanResponse(rows, scheduleCsvFile = "old-1"),
            StudyPlanResponse(rows + rows, scheduleCsvFile = "new-1"),
            StudyPlanResponse(rows + ScheduleRow("external-1", "broken", "2031/03/03/19/00"), scheduleCsvFile = "new-1"),
        )) {
            val store = Store(source)
            val planning = Planning().apply { this.response = response }
            val vm = model(store, Reader(), planning)
            advanceUntilIdle()
            replace(vm)
            assertEquals(source, store.snapshot)
            assertTrue(store.attempts.isEmpty())
            assertEquals(source.plans, vm.plans.value)
            assertEquals(draft, vm.committedDraft.value)
            assertEquals(ConflictPhase.Failed, vm.calendarConflict.value!!.phase)
        }
    }

    @Test fun invalidOriginalIsExplainedWithoutReadQuestionFetchOrReplacement() = runTest(dispatcher) {
        val invalid = source.copy(plans = listOf(source.plans[0].copy(original = request.copy(assessmentResults = AssessmentResults(CERTIFICATION_ID, emptyList())))))
        val reader = Reader()
        val planning = Planning()
        val vm = model(Store(invalid), reader, planning)
        advanceUntilIdle()
        assertNotNull(vm.regenerationBlockReason(1))
        assertFalse(vm.startRegeneration(1))
        assertEquals(0, reader.calls)
        assertEquals(0, questionCalls)
        assertTrue(planning.requests.isEmpty())
        assertEquals(invalid.plans, vm.plans.value)
    }
}
