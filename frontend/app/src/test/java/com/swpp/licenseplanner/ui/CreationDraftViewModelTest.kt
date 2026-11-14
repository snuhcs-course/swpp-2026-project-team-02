package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentOutcome
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.BytesPlanStore
import com.swpp.licenseplanner.data.CalendarReadOutcome
import com.swpp.licenseplanner.data.CalendarReadRepository
import com.swpp.licenseplanner.data.CalendarReadWindow
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
import com.swpp.licenseplanner.domain.CreationDraft
import com.swpp.licenseplanner.domain.CreationStep
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WindowSpec
import com.swpp.licenseplanner.domain.emptyWeek
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
import java.time.LocalTime

/**
 * Saving unfinished creation as a draft, leaving or discarding it, and resuming it after a
 * restart. A restart is a new view model over the same committed bytes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreationDraftViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val scope = PlanStorageScope.of(demo = false, apiBaseUrl = "http://test/api/v1")
    private val disk = MemoryPlanBytes()
    private val today = LocalDate.of(2026, 10, 5)
    private val exam = LocalDate.of(2026, 11, 16)
    private val thirdWeek = LocalDate.of(2026, 10, 19)
    private val fourthWeek = LocalDate.of(2026, 10, 26)

    /** Every network-like call any view model of one test made. */
    private val calls = mutableListOf<String>()

    private inner class CountingQuestions : QuestionRepository {
        override suspend fun load(certificationId: String): QuestionOutcome {
            calls += "questions"
            return QuestionOutcome.Loaded(TestFixtures.publicQuestions())
        }
    }

    private inner class FakePlanning(vararg replies: PlanOutcome) : PlanningRepository {
        val queue = ArrayDeque(replies.toList())
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun generate(request: StudyPlanRequest): PlanOutcome {
            calls += "generate"
            gate?.await()
            return queue.removeFirst()
        }
    }

    private inner class NoAdjustment : AdjustmentRepository {
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
            calls += "adjust"
            error("no adjustment expected")
        }
        override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome {
            calls += "reload"
            error("no reload expected")
        }
    }

    private inner class CountingCalendarRead : CalendarReadRepository {
        override suspend fun read(window: CalendarReadWindow): CalendarReadOutcome {
            calls += "calendar"
            return CalendarReadOutcome.Loaded(com.swpp.licenseplanner.data.CalendarBusySnapshot(emptyList(), 0, 0))
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

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** Starts a process over [disk] and waits for the stored collection. */
    private fun TestScope.open(
        planning: PlanningRepository = FakePlanning(),
        store: GatedStore = GatedStore(),
    ): PlanFormViewModel {
        val vm = PlanFormViewModel(
            CountingQuestions(), planning, NoAdjustment(), today, store,
            calendarReadRepository = CountingCalendarRead(),
        )
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, vm.storage.value)
        return vm
    }

    /** Moves through certification, dates and level to the loaded questions, as the screens do. */
    private fun TestScope.fillToQuestions(vm: PlanFormViewModel) = with(vm) {
        selectCertification(CERTIFICATION_ID)
        reachCreationStep(CreationStep.DATES)
        setExamDate(exam)
        reachCreationStep(CreationStep.LEVEL)
        setLevel(CurrentLevel.INTERMEDIATE)
        reachCreationStep(CreationStep.QUESTIONS)
        loadQuestions()
        advanceUntilIdle()
    }

    /** Answers every question and moves on to availability with Monday enabled. */
    private fun TestScope.fillToAvailability(vm: PlanFormViewModel) = with(vm) {
        fillToQuestions(vm)
        form.value.questions.forEachIndexed { index, question ->
            showQuestion(index)
            answer(question.id, question.correctAnswer)
        }
        reachCreationStep(CreationStep.RESULTS)
        reachCreationStep(CreationStep.AVAILABILITY)
        setDayEnabled(Weekday.MONDAY, true)
    }

    private fun TestScope.storedNow(): PlanSnapshot {
        var result: PlanStoreLoad? = null
        launch { result = BytesPlanStore(MemoryPlanBytes(disk.committed), scope, dispatcher).load() }
        advanceUntilIdle()
        return (result as PlanStoreLoad.Loaded).snapshot
    }

    private fun TestScope.store(draft: CreationDraft) {
        val store = BytesPlanStore(disk, scope, dispatcher)
        launch {
            assertTrue(store.load() is PlanStoreLoad.Loaded)
            assertTrue(store.save(PlanSnapshot(emptyList(), creationDraft = draft)))
        }
        advanceUntilIdle()
    }

    private fun TestScope.saveAndLeave(vm: PlanFormViewModel) {
        assertTrue(vm.saveDraftAndExit())
        advanceUntilIdle()
        assertEquals(DraftDecision(DraftChoice.Save, DraftContinuation.Home, DraftPhase.Done), vm.draftDecision.value)
        vm.draftDecisionHandled()
        assertNull(vm.draftDecision.value)
    }

    // --- Resume position ---

    @Test fun untouchedDefaultsLeaveWithoutADecision() = runTest(dispatcher) {
        val vm = open()
        assertFalse(vm.creationExitNeedsDecision())
        vm.reachCreationStep(CreationStep.DATES)
        assertFalse(vm.creationExitNeedsDecision())
        vm.selectCertification(CERTIFICATION_ID)
        assertTrue(vm.creationExitNeedsDecision())
        assertNull(disk.committed)
    }

    @Test fun quizResumesAtTheSameQuestionWithItsAnswersAfterARestart() = runTest(dispatcher) {
        val first = open()
        fillToQuestions(first)
        val questions = first.form.value.questions
        first.answer(questions[0].id, questions[0].correctAnswer)
        assertTrue(first.showQuestion(1))
        first.answer(questions[1].id, UNKNOWN_KEY)
        assertTrue(first.showQuestion(2))
        // Going back to leave changes only the shown question.
        assertTrue(first.showQuestion(1))
        assertTrue(first.showQuestion(0))
        assertEquals(CreationStep.QUESTIONS, first.creationStep.value)
        val answers = first.form.value.answers
        saveAndLeave(first)
        assertEquals(1, calls.count { it == "questions" })

        val vm = open()
        val stored = vm.committedDraft.value!!
        assertEquals(CreationStep.QUESTIONS, stored.step)
        assertEquals(2, stored.questionIndex)
        assertEquals(CreationStep.QUESTIONS, vm.resumeDraft())
        assertEquals(2, vm.questionIndex.value)
        assertEquals(questions, vm.form.value.questions)
        assertEquals(answers, vm.form.value.answers)
        assertEquals(QuestionLoadState.Loaded, vm.questionLoad.value)
        vm.loadQuestions()
        advanceUntilIdle()
        assertEquals(listOf("questions"), calls)
        assertEquals(stored, vm.committedDraft.value)
    }

    @Test fun backingThroughEarlierStepsKeepsTheCheckpointAndAnEditMovesIt() = runTest(dispatcher) {
        val vm = open()
        fillToAvailability(vm)
        assertTrue(vm.replaceWeek(thirdWeek))
        assertEquals(CreationStep.AVAILABILITY, vm.creationStep.value)

        // Back through results, every question, level, dates and certification to the exit.
        val last = vm.form.value.questions.lastIndex
        for (index in last downTo 0) vm.showQuestion(index)
        assertEquals(CreationStep.AVAILABILITY, vm.creationStep.value)
        vm.reachCreationStep(CreationStep.DATES)
        assertEquals(CreationStep.AVAILABILITY, vm.creationStep.value)
        saveAndLeave(vm)
        assertEquals(CreationDraft(vm.form.value, CreationStep.AVAILABILITY, last, thirdWeek), storedNow().creationDraft)

        // A real change on an earlier screen reopens that screen; an unchanged value does not.
        vm.setLevel(CurrentLevel.INTERMEDIATE)
        assertEquals(CreationStep.AVAILABILITY, vm.creationStep.value)
        vm.setLevel(CurrentLevel.BEGINNER)
        assertEquals(CreationStep.LEVEL, vm.creationStep.value)
        vm.answer(vm.form.value.questions[1].id, UNKNOWN_KEY)
        assertEquals(CreationStep.QUESTIONS, vm.creationStep.value)
        saveAndLeave(vm)
        assertEquals(CreationStep.QUESTIONS, storedNow().creationDraft!!.step)
        assertEquals(1, storedNow().creationDraft!!.questionIndex)
        vm.setPreparationStart(today.plusDays(1))
        assertEquals(CreationStep.DATES, vm.creationStep.value)
        vm.reachCreationStep(CreationStep.LEVEL)
        assertEquals(CreationStep.LEVEL, vm.creationStep.value)
        assertTrue(calls.none { it != "questions" })
    }

    @Test fun replacedWeekResumesWithEveryWindowAndImportedBusyRows() = runTest(dispatcher) {
        val extraWindow = WindowSpec(LocalTime.of(22, 0), 60)
        val base = TestFixtures.gen01Availability() +
            (Weekday.TUESDAY to DayAvailability(false, LocalTime.of(7, 0), 30, listOf(extraWindow)))
        val replaced = base + (Weekday.MONDAY to DayAvailability(true, LocalTime.of(6, 0), 90, listOf(extraWindow)))
        val busy = listOf(ScheduleRow("41", "2026/10/20/09/00", "2026/10/20/12/00", "바쁜 일정"))
        val form = TestFixtures.completeState(availability = base, start = today, exam = exam).copy(
            availabilityOverrides = mapOf(thirdWeek to replaced, fourthWeek to emptyWeek()),
            busySchedules = busy,
        )
        store(CreationDraft(form, CreationStep.AVAILABILITY, questionIndex = 3, availabilityWeek = thirdWeek))

        val vm = open()
        assertFalse(vm.creationExitNeedsDecision())
        assertEquals(CreationStep.AVAILABILITY, vm.resumeDraft())
        assertEquals(form, vm.form.value)
        assertEquals(thirdWeek, vm.availabilityWeek.value)
        assertEquals(3, vm.questionIndex.value)
        assertEquals(CalendarReadState.Loaded(1, 0, 0), vm.calendarRead.value)
        assertEquals(GenerationState.Idle, vm.generation.value)
        assertTrue(calls.isEmpty())
    }

    @Test fun pastDatesStayForCorrectionAndAnUnselectableWeekFallsBack() = runTest(dispatcher) {
        val form = PlanFormState(
            certificationId = CERTIFICATION_ID,
            preparationStart = LocalDate.of(2026, 9, 7),
            examDate = LocalDate.of(2026, 9, 28),
        )
        store(CreationDraft(form, CreationStep.AVAILABILITY, availabilityWeek = LocalDate.of(2027, 1, 4)))

        val vm = open()
        assertEquals(CreationStep.AVAILABILITY, vm.resumeDraft())
        assertEquals(form, vm.form.value)
        assertEquals(LocalDate.of(2026, 9, 7), vm.availabilityWeek.value)
        assertEquals(QuestionLoadState.Idle, vm.questionLoad.value)
        assertEquals(CalendarReadState.Idle, vm.calendarRead.value)
        assertTrue(calls.isEmpty())
    }

    @Test fun generationFailureAndShortfallReopenAvailability() = runTest(dispatcher) {
        val planning = FakePlanning(PlanOutcome.Failure(ApiError.ServerFailure(502)), PlanOutcome.DemoShortfall(12, 3))
        val vm = open(planning)
        fillToAvailability(vm)
        // A date changed from the shortfall warning is followed by a new generation.
        vm.setExamDate(exam.plusWeeks(1))
        assertEquals(CreationStep.DATES, vm.creationStep.value)
        assertTrue(vm.generateAfterCalendarCheck())
        advanceUntilIdle()
        assertTrue(vm.generation.value is GenerationState.Failure)
        assertEquals(CreationStep.AVAILABILITY, vm.creationStep.value)
        vm.setExamDate(exam)
        vm.retryAfterCalendarCheck()
        assertTrue(vm.generateAfterCalendarCheck())
        advanceUntilIdle()
        assertTrue(vm.generation.value is GenerationState.DemoShortfall)
        assertEquals(CreationStep.AVAILABILITY, vm.creationStep.value)
        assertNull(disk.committed)
    }

    // --- Completing a draft ---

    private val generated = StudyPlanResponse(
        listOf(ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/00", "함수")),
        "csv",
        "요약",
        "license_planner/output/study_plan_a.csv",
    )

    @Test fun completingAResumedDraftSavesThePlanOnceAndRemovesTheDraft() = runTest(dispatcher) {
        val first = open()
        fillToAvailability(first)
        saveAndLeave(first)

        val planning = FakePlanning(PlanOutcome.Success(generated, false))
        val vm = open(planning)
        assertEquals(CreationStep.AVAILABILITY, vm.resumeDraft())
        assertTrue(vm.generateAfterCalendarCheck())
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, vm.storage.value)
        val stored = storedNow()
        assertNull(stored.creationDraft)
        assertNull(vm.committedDraft.value)
        assertEquals(listOf(1), stored.plans.map { it.ordinal })
        assertEquals(vm.plans.value, stored.plans)
        assertEquals(1, disk.writes - 1)

        val restarted = open()
        assertNull(restarted.committedDraft.value)
        assertNull(restarted.resumeDraft())
        assertEquals(stored.plans, restarted.plans.value)
        assertEquals(1, calls.count { it == "generate" })
    }

    @Test fun failureShortfallAndAnUnsavedPlanKeepTheSavedDraft() = runTest(dispatcher) {
        val planning = FakePlanning(
            PlanOutcome.Failure(ApiError.ServerFailure(502)),
            PlanOutcome.DemoShortfall(12, 3),
            PlanOutcome.Success(generated, false),
        )
        val vm = open(planning)
        fillToAvailability(vm)
        saveAndLeave(vm)
        val draft = vm.committedDraft.value!!
        val saved = disk.committed

        assertTrue(vm.generateAfterCalendarCheck())
        advanceUntilIdle()
        assertTrue(vm.generation.value is GenerationState.Failure)
        assertTrue(vm.generateAfterCalendarCheck())
        advanceUntilIdle()
        assertTrue(vm.generation.value is GenerationState.DemoShortfall)
        assertArrayEquals(saved, disk.committed)
        assertEquals(draft, vm.committedDraft.value)

        disk.failWrites = true
        assertTrue(vm.generateAfterCalendarCheck())
        advanceUntilIdle()
        assertTrue(vm.generation.value is GenerationState.Success)
        assertEquals(PlanStorage.SaveFailure, vm.storage.value)
        assertArrayEquals(saved, disk.committed)
        assertEquals(draft, vm.committedDraft.value)
        vm.retrySave() // fails again
        advanceUntilIdle()
        assertArrayEquals(saved, disk.committed)

        // A process ending now keeps the draft, invents no plan and generates nothing.
        val restarted = open()
        assertEquals(draft, restarted.committedDraft.value)
        assertTrue(restarted.plans.value.isEmpty())
        assertEquals(3, calls.count { it == "generate" })

        disk.failWrites = false
        val writes = disk.writes
        vm.retrySave()
        vm.retrySave() // a second tap while saving does nothing
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, vm.storage.value)
        assertEquals(writes + 1, disk.writes)
        val stored = storedNow()
        assertNull(stored.creationDraft)
        assertNull(vm.committedDraft.value)
        assertEquals(listOf(1), stored.plans.map { it.ordinal })
        assertEquals(3, calls.count { it == "generate" })
        assertTrue(calls.none { it == "adjust" || it == "reload" })
    }

    // --- Save, discard and stay ---

    @Test fun failedSaveKeepsEarlierBytesAndRetryWritesTheSameCandidateOnce() = runTest(dispatcher) {
        val vm = open()
        vm.selectCertification(CERTIFICATION_ID)
        saveAndLeave(vm)
        val earlier = disk.committed
        val earlierDraft = vm.committedDraft.value

        vm.reachCreationStep(CreationStep.DATES)
        vm.setExamDate(exam)
        val candidate = CreationDraft(vm.form.value, CreationStep.DATES)
        disk.failWrites = true
        assertTrue(vm.saveDraftAndExit())
        advanceUntilIdle()
        assertEquals(DraftDecision(DraftChoice.Save, DraftContinuation.Home, DraftPhase.Failed), vm.draftDecision.value)
        assertEquals(PlanStorage.SaveFailure, vm.storage.value)
        assertArrayEquals(earlier, disk.committed)
        assertEquals(earlierDraft, vm.committedDraft.value)
        assertEquals(candidate.form, vm.form.value)
        assertFalse(vm.saveDraftAndExit())
        assertFalse(vm.discardDraft())
        assertNull(vm.resumeDraft())
        assertFalse(vm.generateAfterCalendarCheck())
        vm.draftDecisionHandled()
        assertEquals(DraftPhase.Failed, vm.draftDecision.value?.phase)

        // A later edit does not change what the retry writes.
        vm.setLevel(CurrentLevel.BEGINNER)
        vm.retrySave() // fails again
        advanceUntilIdle()
        assertEquals(DraftPhase.Failed, vm.draftDecision.value?.phase)
        assertArrayEquals(earlier, disk.committed)
        disk.failWrites = false
        vm.retrySave()
        vm.retrySave() // a second tap while writing does nothing
        advanceUntilIdle()
        assertEquals(DraftDecision(DraftChoice.Save, DraftContinuation.Home, DraftPhase.Done), vm.draftDecision.value)
        assertEquals(PlanStorage.Ready, vm.storage.value)
        assertEquals(candidate, storedNow().creationDraft)
        assertEquals(candidate, vm.committedDraft.value)
        assertEquals(4, disk.writes)
        vm.draftDecisionHandled()
        assertNull(vm.draftDecision.value)
        assertTrue(calls.isEmpty())
    }

    @Test fun delayedSaveRefusesDuplicatesAndContinuesOnce() = runTest(dispatcher) {
        val store = GatedStore()
        val gate = CompletableDeferred<Unit>()
        store.saveGates[1] = gate
        val vm = open(store = store)
        vm.selectCertification(CERTIFICATION_ID)
        assertTrue(vm.saveDraftAndExit())
        advanceUntilIdle()
        assertEquals(DraftPhase.Writing, vm.draftDecision.value?.phase)
        assertEquals(PlanStorage.Saving, vm.storage.value)
        assertNull(vm.committedDraft.value)
        assertFalse(vm.saveDraftAndExit())
        assertFalse(vm.discardDraft())
        assertFalse(vm.stayInCreation())
        vm.draftDecisionHandled()
        assertEquals(DraftPhase.Writing, vm.draftDecision.value?.phase)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(DraftPhase.Done, vm.draftDecision.value?.phase)
        assertFalse(vm.stayInCreation())
        vm.draftDecisionHandled()
        assertNull(vm.draftDecision.value)
        assertEquals(1, store.saves)
        assertEquals(CreationStep.CERTIFICATION, vm.committedDraft.value?.step)
    }

    @Test fun unchangedResumedDraftLeavesWithoutWriting() = runTest(dispatcher) {
        val first = open()
        fillToQuestions(first)
        saveAndLeave(first)
        val bytes = disk.committed
        val writes = disk.writes

        val vm = open()
        assertEquals(CreationStep.QUESTIONS, vm.resumeDraft())
        assertTrue(vm.creationExitNeedsDecision())
        saveAndLeave(vm)
        assertEquals(writes, disk.writes)
        assertArrayEquals(bytes, disk.committed)
    }

    @Test fun stayAfterAFailureKeepsInputsAndWritesNothing() = runTest(dispatcher) {
        val vm = open()
        fillToQuestions(vm)
        val form = vm.form.value
        disk.failWrites = true
        assertTrue(vm.saveDraftAndExit())
        advanceUntilIdle()
        assertEquals(DraftPhase.Failed, vm.draftDecision.value?.phase)

        assertTrue(vm.stayInCreation())
        assertNull(vm.draftDecision.value)
        assertEquals(PlanStorage.Ready, vm.storage.value)
        assertEquals(form, vm.form.value)
        assertNull(disk.committed)
        vm.retrySave() // nothing pending any more
        advanceUntilIdle()
        assertEquals(1, disk.writes)
        assertTrue(vm.stayInCreation())
    }

    @Test fun discardResetsCreationOnlyAfterItIsSaved() = runTest(dispatcher) {
        val vm = open()
        fillToAvailability(vm)
        assertTrue(vm.replaceWeek(thirdWeek))
        saveAndLeave(vm)
        val saved = disk.committed
        val form = vm.form.value

        disk.failWrites = true
        assertTrue(vm.discardDraft())
        advanceUntilIdle()
        assertEquals(DraftDecision(DraftChoice.Discard, DraftContinuation.Home, DraftPhase.Failed), vm.draftDecision.value)
        assertArrayEquals(saved, disk.committed)
        assertEquals(form, vm.form.value)
        assertEquals(QuestionLoadState.Loaded, vm.questionLoad.value)
        assertEquals(thirdWeek, vm.availabilityWeek.value)
        assertTrue(vm.committedDraft.value != null)

        disk.failWrites = false
        vm.retrySave()
        advanceUntilIdle()
        assertEquals(DraftDecision(DraftChoice.Discard, DraftContinuation.Home, DraftPhase.Done), vm.draftDecision.value)
        assertEquals(PlanFormState(preparationStart = today), vm.form.value)
        assertEquals(QuestionLoadState.Idle, vm.questionLoad.value)
        assertEquals(0, vm.questionIndex.value)
        assertNull(vm.availabilityWeek.value)
        assertEquals(CreationStep.CERTIFICATION, vm.creationStep.value)
        assertNull(vm.committedDraft.value)
        assertNull(storedNow().creationDraft)
        assertFalse(vm.creationExitNeedsDecision())
        assertEquals(listOf("questions"), calls)
    }

    @Test fun newCreationReplacesTheDraftOnlyThroughDiscard() = runTest(dispatcher) {
        val first = open()
        fillToQuestions(first)
        saveAndLeave(first)

        val vm = open()
        assertTrue(vm.committedDraft.value != null)
        // Cancelling the replacement choice is no call at all; the draft stays resumable.
        assertTrue(vm.discardDraft(DraftContinuation.NewCreation))
        advanceUntilIdle()
        assertEquals(DraftDecision(DraftChoice.Discard, DraftContinuation.NewCreation, DraftPhase.Done), vm.draftDecision.value)
        assertNull(vm.committedDraft.value)
        assertNull(vm.resumeDraft())
        assertEquals(PlanFormState(preparationStart = today), vm.form.value)

        // With nothing saved, discarding only resets the inputs.
        vm.draftDecisionHandled()
        val writes = disk.writes
        vm.selectCertification(CERTIFICATION_ID)
        assertTrue(vm.discardDraft())
        assertEquals(DraftPhase.Done, vm.draftDecision.value?.phase)
        assertEquals(PlanFormState(preparationStart = today), vm.form.value)
        assertEquals(writes, disk.writes)
    }

    @Test fun draftDecisionsWaitForCalendarReadsAndGeneration() = runTest(dispatcher) {
        val planning = FakePlanning(PlanOutcome.DemoShortfall(12, 3)).apply { gate = CompletableDeferred() }
        val vm = open(planning)
        fillToAvailability(vm)
        assertTrue(vm.startCalendarRead())
        assertFalse(vm.saveDraftAndExit())
        assertFalse(vm.discardDraft())
        vm.onCalendarReadPermission(false)
        assertTrue(vm.saveDraftAndExit())
        advanceUntilIdle()
        vm.draftDecisionHandled()

        assertTrue(vm.generateAfterCalendarCheck())
        assertFalse(vm.saveDraftAndExit())
        assertFalse(vm.discardDraft())
        assertNull(vm.resumeDraft())
        planning.gate!!.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.discardDraft())
        advanceUntilIdle()
        assertEquals(DraftPhase.Done, vm.draftDecision.value?.phase)
        assertEquals(listOf("questions", "calendar", "calendar", "generate"), calls)
    }
}
