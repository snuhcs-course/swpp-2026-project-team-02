package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.*
import com.swpp.licenseplanner.data.model.*
import com.swpp.licenseplanner.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReassessmentViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val now = RemainingStudyFixtures.now
    private val busyReason = "진행 중인 작업을 마친 뒤 다시 시도해 주세요."
    private val recreateReason = "최신 계획을 확인하거나 새 계획을 만들어 주세요."

    /** First diagnostic: 1, 4, 6 and 7 correct, everything else wrong. */
    private val firstDiagnostic = AssessmentResults(CERTIFICATION_ID, TestFixtures.publicQuestions().map { question ->
        val answer = if (question.id in setOf(1, 4, 6, 7)) question.correctAnswer else "A".takeIf { it != question.correctAnswer } ?: "C"
        AssessmentResult(question.id, question.prompt, question.choicesWithUnknown(), question.correctAnswer, answer,
            answer == question.correctAnswer, question.possibleScore)
    })

    private fun row(id: Int, day: Int) = ScheduleRow("study-$id", "2026/11/$day/18/00", "2026/11/$day/19/00", LocalTopics.SPREADSHEET_BASICS)

    /** Plan 1: one past reported row and three unreported rows after [now]. */
    private val a = SessionPlan.register(1, RemainingStudyFixtures.original.copy(assessmentResults = firstDiagnostic),
        StudyPlanResponse(listOf(row(1, 12), row(2, 17), row(3, 18), row(4, 19)), scheduleCsvFile = "plans/a.csv"), false)
        .copy(checkIns = mapOf("study-1" to StudyCheckIn(StudyCheckInStatus.Done, 60)))
    private val b = a.copy(ordinal = 2, scheduleCsvFile = "plans/b.csv", current = a.current.copy(scheduleCsvFile = "plans/b.csv"))

    private val topics = listOf(
        LocalTopics.SPREADSHEET_BASICS, LocalTopics.SPREADSHEET_BASICS, LocalTopics.SPREADSHEET_BASICS, LocalTopics.SPREADSHEET_BASICS,
        LocalTopics.INTERNET_DATA, LocalTopics.INTERNET_DATA, LocalTopics.INTERNET_DATA, LocalTopics.INTERNET_DATA,
        LocalTopics.SYSTEM_USE, LocalTopics.SYSTEM_USE, LocalTopics.SYSTEM_USE,
        LocalTopics.FUNCTIONS_AND_DATA, LocalTopics.FUNCTIONS_AND_DATA,
        LocalTopics.SYSTEM_PROTECTION, LocalTopics.SYSTEM_PROTECTION,
        LocalTopics.CHARTS_AND_ANALYSIS, LocalTopics.PAGE_SETUP_AND_PRINTING, LocalTopics.MACROS,
    )
    private val items = topics.mapIndexed { index, topic ->
        val id = 19 + index
        ReassessmentQuestion(Question(id, "다시 진단 문항 $id", mapOf("A" to "가", "B" to "나", "C" to "다", "D" to "라"), "B", 1.0),
            topic, "문항 $id 해설")
    }

    private class Store(var snapshot: PlanSnapshot) : LocalPlanStore {
        var saves = 0
        override suspend fun load() = PlanStoreLoad.Loaded(snapshot)
        override suspend fun save(snapshot: PlanSnapshot): Boolean { saves++; this.snapshot = snapshot; return true }
    }

    private inner class Questions : ReassessmentRepository {
        var outcomes = mutableListOf<ReassessmentQuestionsOutcome>()
        var loads = 0
        var mutations = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun questions(certificationId: String): ReassessmentQuestionsOutcome {
            loads++
            gate?.await()
            return outcomes.removeFirstOrNull() ?: ReassessmentQuestionsOutcome.Loaded(items)
        }
        override suspend fun preview(input: ReassessmentInput): ReassessmentOutcome {
            mutations++; return ReassessmentOutcome.Failure(ApiError.Configuration)
        }
        override suspend fun commit(input: ReassessmentInput, previewed: List<ScheduleRow>): ReassessmentOutcome {
            mutations++; return ReassessmentOutcome.Failure(ApiError.Configuration)
        }
    }

    private class Other : AdjustmentRepository {
        var reloads = 0
        var gate: CompletableDeferred<Unit>? = null
        var rows: List<ScheduleRow> = emptyList()
        override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome = error("unexpected adjustment")
        override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome { reloads++; gate?.await(); return ReloadOutcome.Loaded(rows) }
    }

    private data class Opened(val vm: PlanFormViewModel, val store: Store, val questions: Questions, val other: Other)

    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    private fun TestScope.open(snapshot: PlanSnapshot = PlanSnapshot(listOf(a, b)), wired: Boolean = true): Opened {
        val store = Store(snapshot)
        val questions = Questions()
        val other = Other()
        val vm = PlanFormViewModel(object : QuestionRepository {
            override suspend fun load(certificationId: String) = QuestionOutcome.Loaded(TestFixtures.publicQuestions())
        }, object : PlanningRepository {
            override suspend fun generate(request: StudyPlanRequest): PlanOutcome = error("unexpected generation")
        }, other, today = now.toLocalDate(), planStore = store, currentDate = { now.toLocalDate() }, currentMinute = { now },
            reassessmentRepository = if (wired) questions else UnavailableReassessment)
        advanceUntilIdle()
        assertEquals(PlanStorage.Ready, vm.storage.value)
        return Opened(vm, store, questions, other)
    }

    private fun TestScope.quiz(o: Opened, ordinal: Int = 1): ReassessmentState.Quiz {
        assertTrue(o.vm.startReassessment(ordinal))
        advanceUntilIdle()
        return o.vm.reassessment.value as ReassessmentState.Quiz
    }

    /** Answers by position: 0–11 correct, 12–15 wrong, 16–17 모르겠음. */
    private fun answer(o: Opened, skip: Int? = null) {
        items.forEachIndexed { index, item ->
            if (index == skip) return@forEachIndexed
            o.vm.answerReassessment(item.question.id, when {
                index < 12 -> "B"
                index < 16 -> "A"
                else -> UNKNOWN_KEY
            })
        }
    }

    private fun assertUnchanged(o: Opened, savesBefore: Int) {
        assertEquals(listOf(a, b), o.vm.plans.value)
        assertEquals(listOf(a, b), o.store.snapshot.plans)
        assertEquals(savesBefore, o.store.saves)
        assertEquals(0, o.questions.mutations)
    }

    @Test fun eligiblePlanOffersReassessmentAndReadsNothingUntilStarted() = runTest(dispatcher) {
        val o = open()
        assertNull(o.vm.reassessmentBlockReason(1))
        assertNull(o.vm.remainingStudyBlockReason(1))
        assertEquals(0, o.questions.loads)
        assertNull(o.vm.reassessment.value)
        quiz(o)
        assertEquals(1, o.questions.loads)
    }

    @Test fun planWithNothingLeftToReviseIsDisabledWithItsNote() = runTest(dispatcher) {
        val reported = a.copy(checkIns = a.current.schedules.associate { it.scheduleId to StudyCheckIn(StudyCheckInStatus.Partly, 20) })
        val past = a.copy(current = a.current.copy(schedules = listOf(row(1, 12), row(2, 13), row(3, 14))), checkIns = emptyMap())
        val examPassed = a.copy(original = a.original.copy(examDate = "2026/11/16/00/00"))
        val startedNow = a.copy(current = a.current.copy(schedules = listOf(row(1, 12),
            ScheduleRow("study-2", "2026/11/16/16/30", "2026/11/16/17/30", LocalTopics.MACROS))), checkIns = emptyMap())
        for (source in listOf(reported, past, examPassed, startedNow)) {
            val o = open(PlanSnapshot(listOf(source)))
            assertEquals(REASSESSMENT_NOTHING_LEFT, o.vm.reassessmentBlockReason(1))
            assertFalse(o.vm.startReassessment(1))
            advanceUntilIdle()
            assertNull(o.vm.reassessment.value)
            assertEquals(0, o.questions.loads)
        }
    }

    @Test fun planThatNeedsRecreationShowsTheRemainingStudyGuidance() = runTest(dispatcher) {
        val withoutWindows = a.copy(original = a.original.copy(weeklyAvailability = emptyMap()))
        val withoutStart = a.copy(original = a.original.copy(preparationStart = ""))
        val withoutIdentity = a.copy(scheduleCsvFile = null, current = a.current.copy(scheduleCsvFile = null))
        val reloadRequired = a.copy(recovery = PlanRecovery.ReloadRequired)
        for (source in listOf(withoutWindows, withoutStart, withoutIdentity, reloadRequired)) {
            val o = open(PlanSnapshot(listOf(source)))
            assertEquals(recreateReason, o.vm.reassessmentBlockReason(1))
            assertFalse(o.vm.startReassessment(1))
            assertEquals(0, o.questions.loads)
        }
        // The plan view shows that guidance once, under both re-plan actions, when the reasons match.
        val o = open(PlanSnapshot(listOf(withoutIdentity)))
        assertEquals(o.vm.remainingStudyBlockReason(1), o.vm.reassessmentBlockReason(1))
    }

    @Test fun anotherOperationOrReloadDisablesEntryAndInvocationStartsNothing() = runTest(dispatcher) {
        // A remaining-study re-plan open for another plan.
        val remaining = open()
        assertTrue(remaining.vm.startRemainingStudy(2))
        assertEquals(busyReason, remaining.vm.reassessmentBlockReason(1))
        assertFalse(remaining.vm.startReassessment(1))
        advanceUntilIdle()
        assertNull(remaining.vm.reassessment.value)
        assertEquals(0, remaining.questions.loads)

        // An explicit reload in flight for another plan.
        val reloading = open(PlanSnapshot(listOf(a, b.copy(recovery = PlanRecovery.ReloadRequired))))
        reloading.other.gate = CompletableDeferred()
        reloading.other.rows = b.current.schedules
        assertTrue(reloading.vm.selectPlan(2)); assertTrue(reloading.vm.reloadPlan()); runCurrent()
        assertEquals(busyReason, reloading.vm.reassessmentBlockReason(1))
        assertFalse(reloading.vm.startReassessment(1))
        assertEquals(0, reloading.questions.loads)
        reloading.other.gate!!.complete(Unit); advanceUntilIdle()
        assertEquals(1, reloading.other.reloads)

        // An open reassessment holds every plan, including its own.
        val open = open()
        quiz(open, 2)
        assertEquals(busyReason, open.vm.reassessmentBlockReason(1))
        assertEquals(busyReason, open.vm.reassessmentBlockReason(2))
        assertEquals(busyReason, open.vm.remainingStudyBlockReason(1))
        assertFalse(open.vm.startReassessment(1))
        assertFalse(open.vm.startRemainingStudy(1))
        assertEquals(2, open.vm.reassessment.value?.source?.ordinal)
        assertEquals(1, open.questions.loads)
    }

    @Test fun leavingFromQuizOrResultsReturnsWithThePlanUnchanged() = runTest(dispatcher) {
        val o = open()
        val saves = o.store.saves
        quiz(o)
        o.vm.answerReassessment(items[0].question.id, "B")
        assertTrue(o.vm.closeReassessment())
        assertNull(o.vm.reassessment.value)
        assertUnchanged(o, saves)

        // A late question load after leaving is ignored.
        o.questions.gate = CompletableDeferred()
        assertTrue(o.vm.startReassessment(1)); runCurrent()
        assertTrue(o.vm.closeReassessment())
        o.questions.gate!!.complete(Unit); advanceUntilIdle()
        assertNull(o.vm.reassessment.value)
        o.questions.gate = null

        quiz(o)
        answer(o)
        assertTrue(o.vm.finishReassessmentQuiz())
        assertTrue(o.vm.reassessment.value is ReassessmentState.Results)
        assertTrue(o.vm.closeReassessment())
        assertNull(o.vm.reassessment.value)
        assertUnchanged(o, saves)
        assertNull(o.vm.reassessmentBlockReason(1))
    }

    @Test fun questionsAppearInServedOrderAndResultsNeedTheLastAnswer() = runTest(dispatcher) {
        val o = open()
        val quiz = quiz(o)
        assertEquals(items, quiz.questions)
        assertEquals(0, quiz.index)
        items.forEachIndexed { index, item ->
            assertFalse(o.vm.finishReassessmentQuiz() && index < items.lastIndex)
            o.vm.answerReassessment(item.question.id, if (index % 5 == 0) UNKNOWN_KEY else "B")
            o.vm.showReassessmentQuestion(index + 1)
        }
        val answered = o.vm.reassessment.value as ReassessmentState.Quiz
        assertTrue(answered.allAnswered)
        assertEquals(UNKNOWN_KEY, answered.answers[19])
        assertTrue(o.vm.finishReassessmentQuiz())
        val results = o.vm.reassessment.value as ReassessmentState.Results
        assertEquals(items, results.questions)
        assertEquals(0, o.questions.mutations)
    }

    @Test fun unansweredQuestionIsShownAndNothingIsSent() = runTest(dispatcher) {
        val o = open()
        quiz(o)
        answer(o, skip = 6)
        o.vm.showReassessmentQuestion(items.lastIndex)
        // An answer key the question does not offer does not count.
        o.vm.answerReassessment(items[6].question.id, "Z")
        assertFalse(o.vm.finishReassessmentQuiz())
        val quiz = o.vm.reassessment.value as ReassessmentState.Quiz
        assertEquals(6, quiz.index)
        assertEquals(6, quiz.firstUnanswered)
        assertEquals(17, quiz.answers.size)
        assertFalse(o.vm.previewReassessment())
        assertEquals(0, o.questions.mutations)
        assertEquals(1, o.questions.loads)
    }

    @Test fun loadFailureOffersRetryAndReturnWithThePlanUnchanged() = runTest(dispatcher) {
        for (error in listOf(ApiError.NetworkUnavailable("offline"), ApiError.ServerFailure(500))) {
            val o = open()
            val saves = o.store.saves
            o.questions.outcomes += ReassessmentQuestionsOutcome.Failure(error)
            assertTrue(o.vm.startReassessment(1)); advanceUntilIdle()
            assertEquals(ReassessmentState.LoadFailure(a, error), o.vm.reassessment.value)
            assertTrue(o.vm.retryReassessmentQuestions()); advanceUntilIdle()
            assertEquals(2, o.questions.loads)
            assertEquals(items, (o.vm.reassessment.value as ReassessmentState.Quiz).questions)
            assertTrue(o.vm.closeReassessment())
            assertUnchanged(o, saves)

            o.questions.outcomes += ReassessmentQuestionsOutcome.Failure(error)
            assertTrue(o.vm.startReassessment(1)); advanceUntilIdle()
            assertTrue(o.vm.closeReassessment())
            assertNull(o.vm.reassessment.value)
            assertUnchanged(o, saves)
        }
    }

    @Test fun serverWithoutReassessmentNeedsAnUpdateAndNoQuestionsStandIn() = runTest(dispatcher) {
        val o = open()
        val saves = o.store.saves
        o.questions.outcomes += ReassessmentQuestionsOutcome.ServerNeedsUpdate
        assertTrue(o.vm.startReassessment(1)); advanceUntilIdle()
        assertEquals(ReassessmentState.ServerNeedsUpdate(a), o.vm.reassessment.value)
        assertFalse(o.vm.retryReassessmentQuestions())
        assertFalse(o.vm.finishReassessmentQuiz())
        assertTrue(o.vm.closeReassessment())
        assertUnchanged(o, saves)

        // Without a flavor repository nothing is substituted either.
        val unwired = open(wired = false)
        assertTrue(unwired.vm.startReassessment(1)); advanceUntilIdle()
        assertEquals(ReassessmentState.LoadFailure(a, ApiError.Configuration), unwired.vm.reassessment.value)
    }

    @Test fun resultsExplainMissedQuestionsAndCompareEachTopicWithTheFirstDiagnostic() = runTest(dispatcher) {
        val o = open()
        quiz(o)
        answer(o)
        assertTrue(o.vm.finishReassessmentQuiz())
        val results = o.vm.reassessment.value as ReassessmentState.Results
        val outcomes = results.questions.map { Scoring.questionResult(it.question, results.answers.getValue(it.question.id)) }
        assertEquals(12, outcomes.count { it == QuestionResult.Correct })
        val missed = results.questions.zip(outcomes).filter { it.second != QuestionResult.Correct }
        assertEquals(items.drop(12), missed.map { it.first })
        assertEquals(List(4) { QuestionResult.Wrong("A. 가", "B. 나") } + List(2) { QuestionResult.Unknown("B. 나") },
            missed.map { it.second })
        assertEquals(items.drop(12).map { "문항 ${it.question.id} 해설" }, missed.map { it.first.explanation })

        val comparisons = topicComparisons(results)
        assertEquals(listOf(
            "${LocalTopics.SPREADSHEET_BASICS} 처음 진단 25% → 이번 진단 100%",
            "${LocalTopics.INTERNET_DATA} 처음 진단 0% → 이번 진단 100%",
            "${LocalTopics.SYSTEM_USE} 처음 진단 67% → 이번 진단 100%",
            "${LocalTopics.FUNCTIONS_AND_DATA} 처음 진단 0% → 이번 진단 50%",
            "${LocalTopics.SYSTEM_PROTECTION} 처음 진단 0% → 이번 진단 0%",
            "${LocalTopics.CHARTS_AND_ANALYSIS} 처음 진단 100% → 이번 진단 0%",
            "${LocalTopics.PAGE_SETUP_AND_PRINTING} 처음 진단 0% → 이번 진단 0%",
            "${LocalTopics.MACROS} 처음 진단 0% → 이번 진단 0%",
        ), comparisons.map { "${it.topic} ${com.swpp.licenseplanner.ui.screens.topicComparisonText(it)}" })
        assertEquals(listOf(4.0, 4.0, 3.0, 1.0, 0.0, 0.0, 0.0, 0.0), comparisons.map { it.earned })
    }

    @Test fun leavingResultsWithoutApplyingKeepsThePlanAndDiscardsTheResults() = runTest(dispatcher) {
        val o = open()
        val saves = o.store.saves
        quiz(o)
        answer(o)
        assertTrue(o.vm.finishReassessmentQuiz())
        assertTrue(o.vm.reassessment.value is ReassessmentState.Results)
        assertTrue(o.vm.closeReassessment())
        assertUnchanged(o, saves)
        assertFalse(o.vm.previewReassessment())
        assertFalse(o.vm.finishReassessmentQuiz())
        val reopened = quiz(o)
        assertTrue(reopened.answers.isEmpty())
        assertEquals(0, reopened.index)
        assertEquals(listOf(a, b), o.vm.plans.value)
    }
}
