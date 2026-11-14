package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.TestFixtures
import com.swpp.licenseplanner.data.AdjustmentInput
import com.swpp.licenseplanner.data.AdjustmentRepository
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.AvailabilityProfileRepository
import com.swpp.licenseplanner.data.AvailabilityWire
import com.swpp.licenseplanner.data.BytesPlanStore
import com.swpp.licenseplanner.data.MemoryPlanBytes
import com.swpp.licenseplanner.data.PlanOutcome
import com.swpp.licenseplanner.data.PlanStorageScope
import com.swpp.licenseplanner.data.PlanningRepository
import com.swpp.licenseplanner.data.ProfileOutcome
import com.swpp.licenseplanner.data.QuestionOutcome
import com.swpp.licenseplanner.data.QuestionRepository
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.StudyAvailability
import com.swpp.licenseplanner.domain.WeekSource
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WindowSpec
import com.swpp.licenseplanner.domain.toEditorDays
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

    /** These tests never adjust; PlanAdjustmentViewModelTest covers adjustment. */
    private class UnusedAdjustment : AdjustmentRepository {
        override suspend fun adjust(input: AdjustmentInput) = error("not used")
        override suspend fun reload(scheduleCsvFile: String?) = error("not used")
    }

    private val questions = TestFixtures.publicQuestions()
    private val plan = StudyPlanResponse(
        schedules = listOf(ScheduleRow("study-1", "2026/10/05/18/00", "2026/10/05/19/30", "셀 참조")),
        agentSummary = "요약",
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    /** The shared server profile, replying in order; each call waits for [gate] when set. */
    private class FakeProfile(vararg replies: ProfileOutcome) : AvailabilityProfileRepository {
        val queue = ArrayDeque(replies.toList())
        var loads = 0
        val saves = mutableListOf<StudyAvailability>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load(): ProfileOutcome {
            loads++
            gate?.await()
            return queue.removeFirst()
        }
        override suspend fun save(availability: StudyAvailability): ProfileOutcome {
            saves += availability
            gate?.await()
            return queue.removeFirst()
        }
    }

    /** Starts with an empty saved collection; restoring it completes on the next advance. */
    private fun viewModel(q: QuestionRepository, p: PlanningRepository, profile: AvailabilityProfileRepository? = null) = PlanFormViewModel(
        q,
        p,
        UnusedAdjustment(),
        today = LocalDate.of(2026, 10, 5),
        planStore = BytesPlanStore(MemoryPlanBytes(), PlanStorageScope.of(demo = false, apiBaseUrl = "http://test/api/v1"), dispatcher),
        availabilityProfileRepository = profile,
    )

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

    private suspend fun TestScope.loadedViewModel(
        planning: PlanningRepository,
        questionRepo: QuestionRepository = FakeQuestions(QuestionOutcome.Loaded(questions)),
        profile: AvailabilityProfileRepository? = null,
    ): PlanFormViewModel =
        viewModel(questionRepo, planning, profile).apply {
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
        assertEquals(150, planning.requests[1].weeklyAvailability.getValue("monday").single().durationMinutes)
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

    // --- Exam-date edit after a demo shortfall (synthetic JVM checks, not a device route) ---

    private val firstShortfall = PlanOutcome.DemoShortfall(required = 30, available = 1)

    /** [later] equals [earlier] except for exam_date, which is [examDate] in wire form. */
    private fun assertOnlyExamDateChanged(earlier: StudyPlanRequest, later: StudyPlanRequest, examDate: String) {
        assertEquals(examDate, later.examDate)
        assertEquals(earlier.copy(examDate = examDate), later)
    }

    @Test fun changedDateRecalculationKeepsEveryOtherInput() = runTest(dispatcher) {
        val questionRepo = FakeQuestions(QuestionOutcome.Loaded(questions))
        val planning = FakePlanning(firstShortfall, PlanOutcome.DemoShortfall(30, 2), PlanOutcome.Success(plan, isDemo = true))
        val vm = loadedViewModel(planning, questionRepo)
        assertTrue(vm.generate())
        advanceUntilIdle()
        val formBefore = vm.form.value

        vm.setExamDate(LocalDate.of(2026, 11, 23))
        assertTrue(vm.generate())
        advanceUntilIdle()
        assertEquals(GenerationState.DemoShortfall(30, 2), vm.generation.value)

        vm.setExamDate(LocalDate.of(2026, 12, 14))
        assertTrue(vm.generate())
        advanceUntilIdle()
        assertEquals(GenerationState.Success(plan, isDemo = true), vm.generation.value)

        assertEquals(3, planning.requests.size)
        assertOnlyExamDateChanged(planning.requests[0], planning.requests[1], "2026/11/23/00/00")
        assertOnlyExamDateChanged(planning.requests[0], planning.requests[2], "2026/12/14/00/00")
        assertEquals(UNKNOWN_KEY, planning.requests[2].assessmentResults.results.single { it.problemId == 1 }.userAnswer)
        assertEquals(formBefore.copy(examDate = LocalDate.of(2026, 12, 14)), vm.form.value)
        assertEquals(1, questionRepo.calls)
        assertEquals(QuestionLoadState.Loaded, vm.questionLoad.value)
    }

    @Test fun delayedChangedDateRecalculationSendsOneRequest() = runTest(dispatcher) {
        val questionRepo = FakeQuestions(QuestionOutcome.Loaded(questions))
        val planning = FakePlanning(firstShortfall, PlanOutcome.Success(plan, isDemo = true))
        val vm = loadedViewModel(planning, questionRepo)
        vm.generate()
        advanceUntilIdle()

        planning.gate = CompletableDeferred()
        vm.setExamDate(LocalDate.of(2026, 12, 14))
        assertTrue(vm.generate())
        assertFalse(vm.generate())
        vm.setExamDate(LocalDate.of(2027, 1, 4)) // ignored while the request is in flight
        advanceUntilIdle()
        assertEquals(GenerationState.Loading, vm.generation.value)
        planning.gate!!.complete(Unit)
        advanceUntilIdle()

        assertEquals(2, planning.requests.size)
        assertOnlyExamDateChanged(planning.requests[0], planning.requests[1], "2026/12/14/00/00")
        assertEquals(LocalDate.of(2026, 12, 14), vm.form.value.examDate)
        assertEquals(GenerationState.Success(plan, isDemo = true), vm.generation.value)
        assertEquals(1, questionRepo.calls)
    }

    @Test fun changedDateFailureRetriesTheIdenticalRequest() = runTest(dispatcher) {
        val questionRepo = FakeQuestions(QuestionOutcome.Loaded(questions))
        val failure = ApiError.ServerFailure(502)
        val planning = FakePlanning(firstShortfall, PlanOutcome.Failure(failure), PlanOutcome.Success(plan, isDemo = false))
        val vm = loadedViewModel(planning, questionRepo)
        vm.generate()
        advanceUntilIdle()

        vm.setExamDate(LocalDate.of(2026, 12, 14))
        vm.generate()
        advanceUntilIdle()
        assertEquals(GenerationState.Failure(failure), vm.generation.value)
        assertEquals(LocalDate.of(2026, 12, 14), vm.form.value.examDate)
        assertTrue(vm.plans.value.isEmpty()) // no fallback plan replaced the failure

        vm.retryGeneration()
        advanceUntilIdle()
        assertEquals(3, planning.requests.size)
        assertOnlyExamDateChanged(planning.requests[0], planning.requests[1], "2026/12/14/00/00")
        assertEquals(planning.requests[1], planning.requests[2])
        assertEquals(GenerationState.Success(plan, isDemo = false), vm.generation.value)
        assertEquals(1, questionRepo.calls)
    }

    // --- Shared server profile ---

    /** Monday 08:00/60 plus a hidden 23:00/90 window, and an explicit no-study week. */
    private val serverProfile = StudyAvailability(
        base = mapOf(Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(8, 0), 60), WindowSpec(LocalTime.of(23, 0), 90))),
        overrides = mapOf(LocalDate.of(2026, 10, 26) to emptyMap()),
    )

    @Test fun profileIsLoadedAndSavedOnlyOnExplicitRequest() = runTest(dispatcher) {
        val profile = FakeProfile(ProfileOutcome.Loaded(serverProfile), ProfileOutcome.Loaded(serverProfile))
        val vm = loadedViewModel(FakePlanning(PlanOutcome.Success(plan, false)), profile = profile)
        assertTrue(vm.profileAvailable)
        val draft = vm.form.value
        assertTrue(vm.generate())
        advanceUntilIdle()
        vm.selectPlan(1)
        vm.setDayEnabled(Weekday.FRIDAY, true)
        vm.setDayEnabled(Weekday.FRIDAY, false)
        assertEquals(0 to 0, profile.loads to profile.saves.size) // generation, navigation and edits never touch it

        profile.gate = CompletableDeferred()
        assertTrue(vm.loadProfile())
        assertFalse(vm.loadProfile())
        assertFalse(vm.saveProfile())
        advanceUntilIdle()
        assertEquals(ProfileState.Loading, vm.profile.value)
        profile.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(ProfileState.Preview(serverProfile), vm.profile.value)
        assertEquals(draft.availability, vm.form.value.availability) // unchanged until accepted
        vm.dismissProfile()
        assertEquals(ProfileState.Idle, vm.profile.value)
        assertEquals(draft.availability, vm.form.value.availability)
        assertEquals(1, profile.loads)

        profile.queue.addFirst(ProfileOutcome.Loaded(serverProfile))
        assertTrue(vm.loadProfile())
        advanceUntilIdle()
        vm.acceptProfile()
        assertEquals(ProfileState.Idle, vm.profile.value)
        assertEquals(serverProfile.base.toEditorDays(), vm.form.value.availability)
        assertEquals(serverProfile, vm.form.value.studyAvailability) // the hidden window and the empty week are kept
        assertEquals(1, vm.form.value.availability.getValue(Weekday.MONDAY).extras.size)

        assertTrue(vm.saveProfile())
        advanceUntilIdle()
        assertEquals(ProfileState.Saved, vm.profile.value)
        assertEquals(listOf(serverProfile), profile.saves)
        assertEquals(2, profile.loads)
        assertTrue(profile.queue.isEmpty())
    }

    @Test fun failedProfileActionsKeepTheDraftAndRetryOnlyOnRequest() = runTest(dispatcher) {
        val profile = FakeProfile(
            ProfileOutcome.Failure(ApiError.Connection),
            ProfileOutcome.Failure(ApiError.InvalidResponse("saved profile differs")),
            ProfileOutcome.Loaded(StudyAvailability(emptyMap())),
        )
        val vm = loadedViewModel(FakePlanning(), profile = profile)
        val draft = vm.form.value
        assertTrue(vm.loadProfile())
        advanceUntilIdle()
        assertEquals(ProfileState.Failure(ApiError.Connection, saving = false), vm.profile.value)
        vm.acceptProfile() // nothing to accept
        assertEquals(draft, vm.form.value)

        assertTrue(vm.saveProfile())
        advanceUntilIdle()
        assertEquals(ProfileState.Failure(ApiError.InvalidResponse("saved profile differs"), saving = true), vm.profile.value)
        assertEquals(draft, vm.form.value)
        assertEquals(1 to 1, profile.loads to profile.saves.size) // no automatic retry

        assertTrue(vm.saveProfile()) // the explicit retry sends the same complete draft
        advanceUntilIdle()
        assertEquals(listOf(draft.studyAvailability, draft.studyAvailability), profile.saves)
        assertEquals(ProfileState.Saved, vm.profile.value)

        // An overlapping context is never saved.
        vm.setDayStart(Weekday.MONDAY, LocalTime.of(23, 0))
        vm.setDayEnabled(Weekday.TUESDAY, true)
        vm.setDayStart(Weekday.TUESDAY, LocalTime.of(0, 30))
        assertTrue(vm.form.value.availabilityError != null)
        assertFalse(vm.saveProfile())
        advanceUntilIdle()
        assertEquals(2, profile.saves.size)
    }

    @Test fun demoHasNoServerProfileActions() = runTest(dispatcher) {
        val vm = loadedViewModel(FakePlanning())
        assertFalse(vm.profileAvailable)
        assertFalse(vm.loadProfile())
        assertFalse(vm.saveProfile())
        assertEquals(ProfileState.Idle, vm.profile.value)
    }

    // --- Week replacements and retained windows ---

    private val oct12 = LocalDate.of(2026, 10, 12)
    private val oct19 = LocalDate.of(2026, 10, 19)

    /** Two Monday base windows, a two-window replaced week and a replaced week after the exam. */
    private val multiWindowProfile = StudyAvailability(
        base = mapOf(Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(8, 0), 60), WindowSpec(LocalTime.of(23, 0), 90))),
        overrides = mapOf(
            oct12 to mapOf(Weekday.TUESDAY to listOf(WindowSpec(LocalTime.of(7, 0), 30), WindowSpec(LocalTime.of(20, 0), 60))),
            LocalDate.of(2026, 12, 7) to mapOf(Weekday.FRIDAY to listOf(WindowSpec(LocalTime.of(9, 0), 60))),
        ),
    )

    private suspend fun TestScope.withProfile(profile: FakeProfile, planning: PlanningRepository): PlanFormViewModel =
        loadedViewModel(planning, profile = profile).apply {
            assertTrue(loadProfile())
            advanceUntilIdle()
            acceptProfile()
            assertEquals(multiWindowProfile, form.value.studyAvailability)
        }

    @Test fun weekControlsReplaceEmptyAndResetOnlyPreparationWeeks() = runTest(dispatcher) {
        val planning = FakePlanning(PlanOutcome.Success(plan, false))
        val vm = withProfile(FakeProfile(ProfileOutcome.Loaded(multiWindowProfile)), planning)
        val weeks = vm.form.value.studyWeeks
        assertEquals(LocalDate.of(2026, 10, 5), weeks.first())
        assertEquals(LocalDate.of(2026, 11, 9), weeks.last()) // the exam's own week has no study day
        assertEquals(weeks.first(), vm.form.value.shownWeek(vm.availabilityWeek.value))

        // Weeks outside the preparation period cannot be shown, replaced, emptied or edited.
        val before = vm.form.value
        for (outside in listOf(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 11, 16), LocalDate.of(2026, 12, 7))) {
            assertFalse(vm.showAvailabilityWeek(outside))
            assertFalse(vm.replaceWeek(outside))
            assertFalse(vm.clearWeek(outside))
            assertFalse(vm.resetWeek(outside))
        }
        vm.setDayEnabled(Weekday.MONDAY, true, LocalDate.of(2026, 12, 7))
        assertEquals(before, vm.form.value)

        // Replacing starts from the base, unshown windows included, and edits only that week.
        assertTrue(vm.replaceWeek(oct19))
        assertEquals(oct19, vm.availabilityWeek.value)
        assertEquals(WeekSource.REPLACED, vm.form.value.weekSource(oct19))
        assertEquals(multiWindowProfile.base, vm.form.value.studyAvailability.overrides.getValue(oct19))
        assertFalse(vm.setDuration(Weekday.WEDNESDAY, 1440, oct19))
        assertTrue(vm.setDuration(Weekday.WEDNESDAY, 90, oct19))
        assertEquals(listOf(WindowSpec(LocalTime.of(18, 0), 90)), vm.form.value.studyAvailability.overrides.getValue(oct19)[Weekday.WEDNESDAY])
        assertEquals(multiWindowProfile.base, vm.form.value.studyAvailability.base)

        // An empty week has no windows of its own; reset removes the key and inherits the base.
        assertTrue(vm.clearWeek(oct19))
        assertEquals(WeekSource.EMPTY, vm.form.value.weekSource(oct19))
        assertEquals(emptyMap<Weekday, List<WindowSpec>>(), vm.form.value.studyAvailability.overrides.getValue(oct19))
        assertTrue(vm.form.value.studyAvailability.windowsStarting(oct19).isEmpty())
        assertTrue(vm.generate())
        advanceUntilIdle()
        val sent = AvailabilityWire.context(planning.requests.single())!!
        assertEquals(emptyMap<Weekday, List<WindowSpec>>(), sent.overrides.getValue(oct19))
        assertTrue(LocalDate.of(2026, 12, 7) in sent.overrides) // kept, though never shown

        assertTrue(vm.resetWeek(oct19))
        assertEquals(WeekSource.BASE, vm.form.value.weekSource(oct19))
        assertFalse(oct19 in vm.form.value.studyAvailability.overrides)
        assertEquals(multiWindowProfile.base[Weekday.MONDAY], vm.form.value.studyAvailability.windowsStarting(oct19))
        assertEquals(multiWindowProfile, vm.form.value.studyAvailability)
    }

    @Test fun editingVisibleWindowsKeepsHiddenWindowsThroughSaveAndGeneration() = runTest(dispatcher) {
        val profile = FakeProfile(ProfileOutcome.Loaded(multiWindowProfile), ProfileOutcome.Loaded(StudyAvailability(emptyMap())))
        val planning = FakePlanning(PlanOutcome.Success(plan, false))
        val vm = withProfile(profile, planning)

        // The primary is the earliest window; the base Monday shows 08:00 and keeps 23:00.
        vm.stepDuration(Weekday.MONDAY, 1)
        vm.setDayEnabled(Weekday.TUESDAY, false, oct12)
        val monday = vm.form.value.availability.getValue(Weekday.MONDAY)
        assertEquals(1, monday.extras.size)
        assertEquals(1, vm.form.value.availabilityOverrides.getValue(oct12).getValue(Weekday.TUESDAY).extras.size)
        val expected = StudyAvailability(
            base = mapOf(Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(8, 0), 90), WindowSpec(LocalTime.of(23, 0), 90))),
            overrides = multiWindowProfile.overrides + (oct12 to mapOf(Weekday.TUESDAY to listOf(WindowSpec(LocalTime.of(20, 0), 60)))),
        )
        assertEquals(expected, vm.form.value.studyAvailability)

        // Disabling the base primary still leaves its hidden window.
        vm.setDayEnabled(Weekday.MONDAY, false)
        assertEquals(listOf(WindowSpec(LocalTime.of(23, 0), 90)), vm.form.value.studyAvailability.base[Weekday.MONDAY])
        vm.setDayEnabled(Weekday.MONDAY, true)
        assertEquals(expected, vm.form.value.studyAvailability)

        assertTrue(vm.saveProfile())
        advanceUntilIdle()
        assertEquals(listOf(expected), profile.saves)
        assertEquals(ProfileState.Saved, vm.profile.value)
        assertTrue(vm.generate())
        advanceUntilIdle()
        assertEquals(expected, AvailabilityWire.context(planning.requests.single()))
        assertEquals(1, vm.plans.value.size)
        assertEquals(ProfileState.Saved, vm.profile.value) // generation does not touch the profile

        vm.setDayStart(Weekday.MONDAY, LocalTime.of(9, 0))
        assertEquals(ProfileState.Idle, vm.profile.value) // the saved notice described the earlier draft
    }

    @Test fun crossMidnightConflictBlocksGenerationAndSave() = runTest(dispatcher) {
        val profile = FakeProfile()
        val planning = FakePlanning()
        val vm = loadedViewModel(planning, profile = profile)
        vm.setDayStart(Weekday.MONDAY, LocalTime.of(23, 0))
        vm.setDuration(Weekday.MONDAY, 90)
        vm.setDuration(Weekday.TUESDAY, 60)
        vm.setDayStart(Weekday.TUESDAY, LocalTime.of(0, 15))
        val draft = vm.form.value
        assertTrue(draft.availabilityError != null)
        assertFalse(vm.generate())
        assertFalse(vm.saveProfile())
        advanceUntilIdle()
        assertTrue(planning.requests.isEmpty())
        assertTrue(profile.saves.isEmpty())
        assertEquals(draft, vm.form.value) // both windows stay as entered

        vm.setDayStart(Weekday.TUESDAY, LocalTime.of(0, 30)) // adjacent to the Monday spill
        assertEquals(null, vm.form.value.availabilityError)
    }

    @Test fun fullListEditsStayScopedAndRejectStaleTargets() = runTest(dispatcher) {
        val vm = loadedViewModel(FakePlanning())
        val original = vm.form.value
        val append = vm.captureWindow(Weekday.MONDAY, null)!!
        assertEquals(original, vm.form.value)
        assertTrue(vm.applyWindow(append, LocalTime.of(22, 0), 60))
        assertFalse(vm.applyWindow(append, LocalTime.of(23, 0), 60))
        val second = vm.captureWindow(Weekday.MONDAY, 1)!!
        assertTrue(vm.applyWindow(second, LocalTime.of(8, 0), 60))
        assertEquals(listOf(LocalTime.of(8, 0), LocalTime.of(18, 0)), vm.form.value.availability.getValue(Weekday.MONDAY).windows().map { it.start })
        val base = vm.form.value.availability
        assertTrue(vm.replaceWeek(oct12))
        val replacement = vm.captureWindow(Weekday.MONDAY, 0, oct12)!!
        assertTrue(vm.removeWindow(replacement))
        assertEquals(base, vm.form.value.availability)
        assertEquals(listOf(LocalTime.of(18, 0)), vm.form.value.availabilityOverrides.getValue(oct12).getValue(Weekday.MONDAY).windows().map { it.start })
        assertTrue(vm.removeWindow(vm.captureWindow(Weekday.MONDAY, 0, oct12)!!))
        assertEquals(WeekSource.EMPTY, vm.form.value.weekSource(oct12))
        assertTrue(oct12 in vm.form.value.availabilityOverrides)
        val before = vm.form.value
        val canceled = vm.captureWindow(Weekday.MONDAY, null, oct12)!!
        assertEquals(before, vm.form.value)
        assertTrue(vm.showAvailabilityWeek(oct19))
        assertFalse(vm.applyWindow(canceled, LocalTime.of(9, 0), 60))
        assertFalse(vm.removeWindow(second))
        assertEquals(before, vm.form.value)
        assertEquals(original.availabilityOverrides, vm.form.value.availabilityOverrides - oct12)
    }

    @Test fun fullListInvalidDurationsAndOverlapsRemainCorrectableWithoutSending() = runTest(dispatcher) {
        val planning = FakePlanning()
        val profile = FakeProfile()
        val vm = loadedViewModel(planning, profile = profile)
        val append = vm.captureWindow(Weekday.MONDAY, null)!!
        val before = vm.form.value
        for (minutes in listOf(0, -1, 1440, Int.MAX_VALUE)) {
            assertFalse(vm.applyWindow(append, LocalTime.of(20, 0), minutes))
            assertEquals(before, vm.form.value)
        }
        assertFalse(vm.applyWindow(append, LocalTime.of(20, 0, 1), 60))
        assertTrue(vm.applyWindow(append, LocalTime.of(19, 0), 60))
        assertTrue(vm.form.value.availabilityError != null)
        assertFalse(vm.generate())
        assertFalse(vm.saveProfile())
        advanceUntilIdle()
        assertTrue(planning.requests.isEmpty())
        assertTrue(profile.saves.isEmpty())
        assertEquals(2, vm.form.value.availability.getValue(Weekday.MONDAY).windows().size)
        assertTrue(vm.applyWindow(vm.captureWindow(Weekday.MONDAY, 1)!!, LocalTime.of(20, 0), 60))
        assertEquals(null, vm.form.value.availabilityError)
    }

    @Test fun splitWindowsSurviveProfileSaveAndRequestWithExplicitEmptyWeek() = runTest(dispatcher) {
        val windows = StudyAvailability(
            base = mapOf(
                Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(8, 0), 60), WindowSpec(LocalTime.of(19, 0), 120)),
                Weekday.SUNDAY to listOf(WindowSpec(LocalTime.of(23, 30), 60)),
            ),
            overrides = mapOf(
                oct12 to mapOf(Weekday.MONDAY to listOf(WindowSpec(LocalTime.of(9, 0), 60), WindowSpec(LocalTime.of(19, 0), 120))),
                oct19 to emptyMap(),
            ),
        )
        val profile = FakeProfile(ProfileOutcome.Loaded(windows), ProfileOutcome.Loaded(windows))
        val planning = FakePlanning(PlanOutcome.Success(plan, false))
        val vm = loadedViewModel(planning, profile = profile)
        assertTrue(vm.loadProfile())
        advanceUntilIdle()
        vm.acceptProfile()
        assertEquals(windows, vm.form.value.studyAvailability)
        assertTrue(vm.saveProfile())
        advanceUntilIdle()
        assertEquals(listOf(windows), profile.saves)
        assertTrue(vm.generate())
        advanceUntilIdle()
        val sent = planning.requests.single()
        assertEquals(2, sent.studyDaysPerWeek)
        assertEquals(windows, AvailabilityWire.context(sent))
        assertEquals(windows, AvailabilityWire.context(vm.plans.value.single().original))
    }

}
