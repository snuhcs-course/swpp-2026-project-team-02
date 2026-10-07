package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.PlanOutcome
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.time.LocalTime

@OptIn(ExperimentalCoroutinesApi::class)
class PlanFormViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    /** Replies in order; each call waits for [gate] when set. */
    private class FakeQuestions(vararg replies: QuestionOutcome) : QuestionRepository {
        val queue = ArrayDeque(replies.toList())
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(certificationId: String): QuestionOutcome {
            calls++
            gate?.await()
            return queue.removeFirst()
        }
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

    private val questions = TestFixtures.publicQuestions()
    private val plan = StudyPlanResponse(
        schedules = listOf(ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/30", "셀 참조")),
        agentSummary = "요약",
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(q: QuestionRepository, p: PlanningRepository) =
        PlanFormViewModel(q, p, today = LocalDate.of(2026, 10, 5))

    /** Certification, dates and level chosen; questions not yet loaded. */
    private fun PlanFormViewModel.fillBeforeAssessment() {
        selectCertification(CERTIFICATION_ID)
        setExamDate(LocalDate.of(2026, 11, 16))
        setLevel(CurrentLevel.INTERMEDIATE)
    }

    private fun PlanFormViewModel.fillAfterAssessment() {
        for (question in form.value.questions) {
            answer(question.id, if (question.id == 1) UNKNOWN_KEY else question.correctAnswer)
        }
        setDayEnabled(Weekday.MONDAY, true)
        setDayStart(Weekday.MONDAY, LocalTime.of(18, 0))
        stepDuration(Weekday.MONDAY, 2)
    }

    private suspend fun TestScope.loadedViewModel(planning: PlanningRepository): PlanFormViewModel =
        viewModel(FakeQuestions(QuestionOutcome.Loaded(questions)), planning).apply {
            fillBeforeAssessment()
            loadQuestions()
            advanceUntilIdle()
            fillAfterAssessment()
        }

    @Test fun loadsQuestionsOnceAndIgnoresDuplicateLoads() = runTest(dispatcher) {
        val repo = FakeQuestions(QuestionOutcome.Loaded(questions)).apply { gate = CompletableDeferred() }
        val vm = viewModel(repo, FakePlanning())
        vm.fillBeforeAssessment()

        vm.loadQuestions()
        vm.loadQuestions()
        advanceUntilIdle()
        assertEquals(QuestionLoadState.Loading, vm.questionLoad.value)
        repo.gate!!.complete(Unit)
        advanceUntilIdle()
        vm.loadQuestions()
        advanceUntilIdle()

        assertEquals(1, repo.calls)
        assertEquals(QuestionLoadState.Loaded, vm.questionLoad.value)
        assertEquals(questions, vm.form.value.questions)
    }

    @Test fun loadFailureKeepsInputsAndRetryLoads() = runTest(dispatcher) {
        val repo = FakeQuestions(QuestionOutcome.Failure(ApiError.Connection), QuestionOutcome.Loaded(questions))
        val vm = viewModel(repo, FakePlanning())
        vm.fillBeforeAssessment()
        val before = vm.form.value

        vm.loadQuestions()
        advanceUntilIdle()
        assertEquals(QuestionLoadState.Failure(ApiError.Connection), vm.questionLoad.value)
        assertEquals(before, vm.form.value)
        assertTrue(vm.form.value.questions.isEmpty())

        vm.loadQuestions()
        advanceUntilIdle()
        assertEquals(2, repo.calls)
        assertEquals(QuestionLoadState.Loaded, vm.questionLoad.value)
        assertEquals(before.copy(questions = questions), vm.form.value)
    }

    @Test fun returningToAssessmentKeepsSetAndAnswersWithoutRefetch() = runTest(dispatcher) {
        val repo = FakeQuestions(QuestionOutcome.Loaded(questions))
        val vm = viewModel(repo, FakePlanning())
        vm.fillBeforeAssessment()
        vm.loadQuestions()
        advanceUntilIdle()
        vm.answer(1, "B")
        vm.answer(2, UNKNOWN_KEY)

        vm.loadQuestions() // the questions screen is entered again
        advanceUntilIdle()

        assertEquals(1, repo.calls)
        assertEquals(questions, vm.form.value.questions)
        assertEquals(mapOf(1 to "B", 2 to UNKNOWN_KEY), vm.form.value.answers)
    }

    @Test fun generationBlockedUntilEveryLoadedQuestionIsAnswered() = runTest(dispatcher) {
        val planning = FakePlanning(PlanOutcome.Success(plan, isDemo = false))
        val vm = viewModel(FakeQuestions(QuestionOutcome.Loaded(questions)), planning)
        vm.fillBeforeAssessment()
        vm.loadQuestions()
        advanceUntilIdle()
        vm.setDayEnabled(Weekday.MONDAY, true)
        questions.dropLast(1).forEach { vm.answer(it.id, it.correctAnswer) }

        assertFalse(vm.generate())
        advanceUntilIdle()
        assertTrue(planning.requests.isEmpty())
        assertEquals(GenerationState.Idle, vm.generation.value)

        vm.answer(questions.last().id, UNKNOWN_KEY)
        assertTrue(vm.generate())
        advanceUntilIdle()
        assertEquals(1, planning.requests.size)
        assertEquals(questions.map { it.id }, planning.requests[0].assessmentResults.results.map { it.problemId })
        assertEquals(GenerationState.Success(plan, isDemo = false), vm.generation.value)
    }

    @Test fun twoRapidGenerateCallsSendOneRequest() = runTest(dispatcher) {
        val planning = FakePlanning(PlanOutcome.Success(plan, isDemo = false)).apply { gate = CompletableDeferred() }
        val vm = loadedViewModel(planning)

        assertTrue(vm.generate())
        assertFalse(vm.generate())
        advanceUntilIdle()
        assertEquals(GenerationState.Loading, vm.generation.value)
        planning.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, planning.requests.size)
        assertEquals(GenerationState.Success(plan, isDemo = false), vm.generation.value)
    }

    /** The demo planner never suspends; on Main.immediate its result is set before generate() returns. */
    @Test fun immediateLocalResultStillReportsTheRequestAsSent() = runTest(dispatcher) {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        val planning = FakePlanning(PlanOutcome.DemoShortfall(required = 24, available = 6))
        val vm = loadedViewModel(planning)

        assertTrue(vm.generate())
        assertEquals(GenerationState.DemoShortfall(24, 6), vm.generation.value)
        assertEquals(1, planning.requests.size)
    }

    @Test fun planningFailureKeepsFormAndRetryResendsIdenticalRequest() = runTest(dispatcher) {
        val failure = ApiError.ServerFailure(502)
        val planning = FakePlanning(PlanOutcome.Failure(failure), PlanOutcome.Success(plan, isDemo = false))
        val vm = loadedViewModel(planning)
        val formBefore = vm.form.value

        vm.generate()
        advanceUntilIdle()
        assertEquals(GenerationState.Failure(failure), vm.generation.value)
        assertEquals(formBefore, vm.form.value)
        assertEquals(QuestionLoadState.Loaded, vm.questionLoad.value)

        vm.retryGeneration()
        advanceUntilIdle()
        assertEquals(2, planning.requests.size)
        assertEquals(planning.requests[0], planning.requests[1])
        assertEquals(GenerationState.Success(plan, isDemo = false), vm.generation.value)
    }

    @Test fun editingAfterFailureBuildsANewRequest() = runTest(dispatcher) {
        val planning = FakePlanning(PlanOutcome.Failure(ApiError.Connection), PlanOutcome.Success(plan, isDemo = false))
        val vm = loadedViewModel(planning)
        vm.generate()
        advanceUntilIdle()

        vm.stepDuration(Weekday.MONDAY, 1)
        assertEquals(GenerationState.Idle, vm.generation.value)
        vm.retryGeneration() // nothing to retry once inputs changed
        advanceUntilIdle()
        assertEquals(1, planning.requests.size)

        vm.generate()
        advanceUntilIdle()
        assertEquals(2, planning.requests.size)
        assertEquals("20:30", planning.requests[1].weeklyAvailability.getValue("monday").single().end)
    }

    @Test fun liveErrorsNeverProduceDemoData() = runTest(dispatcher) {
        val errors = listOf(
            ApiError.InvalidInput("exam_date must be after preparation_start"),
            ApiError.Configuration,
            ApiError.ServerUnavailable(null),
            ApiError.ServerFailure(500),
            ApiError.Connection,
            ApiError.InvalidResponse("bad body"),
        )
        for (error in errors) {
            val planning = FakePlanning(PlanOutcome.Failure(error))
            val vm = loadedViewModel(planning)
            vm.generate()
            advanceUntilIdle()
            assertEquals(GenerationState.Failure(error), vm.generation.value)
            assertEquals(questions, vm.form.value.questions)
        }

        val failedQuestions = viewModel(FakeQuestions(QuestionOutcome.Failure(ApiError.ServerFailure(500))), FakePlanning())
        failedQuestions.fillBeforeAssessment()
        failedQuestions.loadQuestions()
        advanceUntilIdle()
        assertTrue(failedQuestions.form.value.questions.isEmpty())
        failedQuestions.generate()
        advanceUntilIdle()
        assertFalse(failedQuestions.generation.value is GenerationState.Success)
        assertEquals(GenerationState.Idle, failedQuestions.generation.value)
    }
}
