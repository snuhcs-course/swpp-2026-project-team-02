package com.swpp.licenseplanner.data

import androidx.core.util.AtomicFile
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.swpp.licenseplanner.BuildConfig
import com.swpp.licenseplanner.MainActivity
import com.swpp.licenseplanner.data.model.AssessmentResult
import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.Scoring
import com.swpp.licenseplanner.domain.Question
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import com.swpp.licenseplanner.ui.PlanDeletionStatus
import com.swpp.licenseplanner.ui.PlanFormViewModel
import com.swpp.licenseplanner.ui.PlanStorage
import com.swpp.licenseplanner.ui.QuestionLoadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Minimal Android live-flavor E2E path.
 *
 * The test intentionally targets the checked-in Python fixed fixture through
 * the live-flavor BuildConfig URL. It exercises Android's real OkHttp transport
 * and serializers, then the production Python handler, scheduler, adjustment
 * rules, stored exam deadline, and plan store.
 * It submits every loaded onboarding question, as a completed assessment does,
 * which the fixture's default synthetic trace plans by the reviewed topics.
 * CI maps emulator loopback to the fixture with `adb reverse`; no OpenRouter
 * request is made by the fixture. It lives in the live-only instrumentation
 * source set, so demo instrumentation never discovers it.
 */
@RunWith(AndroidJUnit4::class)
class LiveBackendE2eTest {
    private val wire = DateTimeFormatter.ofPattern("yyyy/MM/dd/HH/mm")

    @Test
    fun loadsQuestions_generatesFullPlan_movesSessionWithConfirmation_andReadsBack() = runBlocking {
        assertFalse("Run this test with connectedLiveDebugAndroidTest", BuildConfig.DEMO_MODE)
        assertTrue("live API base URL must be configured", BuildConfig.API_BASE_URL.isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokens = DeviceTokenSource { DeviceTokenStore.open(context).token() }
        val previousPlan = InstrumentationRegistry.getArguments().getString("readBackPlan")
        if (previousPlan != null) {
            assertTrue(readBack(FlavorRepositories.adjustmentRepository(tokens), previousPlan).isNotEmpty())
            return@runBlocking
        }
        val questions = ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var viewModel: PlanFormViewModel
            activity.onActivity {
                viewModel = ViewModelProvider(it, PlanFormViewModel.Factory)[PlanFormViewModel::class.java]
                viewModel.selectCertification(CERTIFICATION_ID)
                viewModel.loadQuestions()
            }
            val state = withTimeout(15_000) {
                viewModel.questionLoad.first { it is QuestionLoadState.Loaded || it is QuestionLoadState.Failure }
            }
            assertEquals(QuestionLoadState.Loaded, state)
            viewModel.form.value.questions
        }
        assertEquals((1..18).toList(), questions.map { it.id })

        val request = fixtureRequest(questions)
        val plan = generate(tokens, request)
        assertTrue(plan.schedules.isNotEmpty())
        // The backend's reviewed topics for both written subjects.
        val computerGeneral = setOf("컴퓨터 시스템 활용", "인터넷 자료 활용", "컴퓨터 시스템 보호")
        val spreadsheet = setOf("스프레드시트 기본", "함수와 데이터 관리", "차트와 분석", "페이지 설정과 인쇄", "매크로 활용")
        assertEquals(computerGeneral + spreadsheet, plan.schedules.map { it.topic }.filter { it.isNotEmpty() }.toSet())
        val identity = requireNotNull(plan.scheduleCsvFile) { "fixture must return a saved-plan identity" }

        val adjustments = FlavorRepositories.adjustmentRepository(tokens)
        assertEquals(plan.schedules, readBack(adjustments, identity))

        val target = plan.schedules.first { it.scheduleId.startsWith("study-") }
        val input = AdjustmentInput(request, identity, plan.schedules, target.scheduleId)
        val automatic = adjustments.adjust(input)
        val error = (automatic as? AdjustmentOutcome.Failure)?.error
        assertTrue("expected no free slot in the filled study windows, got $automatic",
            error is ApiError.InvalidInput && error.message?.startsWith("NOT_ENOUGH_TIME:") == true)
        assertEquals(plan.schedules, readBack(adjustments, identity))

        // This Saturday is outside the study windows. The fixture clock is frozen before it.
        val destination = LocalDateTime.of(2026, 10, 24, 10, 0)
        val confirmed = input.copy(manualMove = ManualMove(destination, confirmOutsideAvailability = true))
        val adjusted = when (val outcome = adjustments.adjust(confirmed)) {
            is AdjustmentOutcome.Success -> outcome.response.schedules
            AdjustmentOutcome.DemoNoSlot -> throw AssertionError("live repository returned a demo-only result")
            is AdjustmentOutcome.Failure -> throw AssertionError("adjustment failed: ${outcome.error}")
        }
        val moved = adjusted.single { it.scheduleId == target.scheduleId }
        val start = LocalDateTime.parse(moved.startDate, wire)
        val end = LocalDateTime.parse(moved.endDate, wire)
        assertEquals(destination, start)
        assertTrue("the whole session must end by the original exam", !end.isAfter(LocalDateTime.parse(request.examDate, wire)))
        assertEquals(target.topic, moved.topic)
        assertEquals(
            plan.schedules.filter { it.scheduleId != target.scheduleId },
            adjusted.filter { it.scheduleId != target.scheduleId },
        )
        assertEquals(adjusted, readBack(adjustments, identity))

        val credentialFile = File(context.noBackupFilesDir, DeviceTokenStore.FILE_NAME)
        val reopened = DeviceTokenStore(AtomicFilePlanStore.AtomicFileBytes(AtomicFile(credentialFile)))
        assertEquals(adjusted, readBack(readerWith(reopened), identity))

        val otherFile = File.createTempFile("other-device-", ".credential", context.cacheDir)
        check(otherFile.delete())
        val otherCredential = AtomicFile(otherFile)
        try {
            val other = DeviceTokenStore(AtomicFilePlanStore.AtomicFileBytes(otherCredential))
            assertEquals(ReloadOutcome.Missing, readerWith(other).reload(identity))
        } finally {
            otherCredential.delete()
        }
    }

    @Test
    fun appDeletesAnOwnedSavedPlanAndItsServerCopy() = runBlocking {
        assertTrue(BuildConfig.API_BASE_URL.toHttpUrl().host in setOf("127.0.0.1", "10.0.2.2"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tokens = DeviceTokenSource { DeviceTokenStore.open(context).token() }
        val questions = (FlavorRepositories.questionRepository(tokens).load(CERTIFICATION_ID) as QuestionOutcome.Loaded).questions
        val request = fixtureRequest(questions)
        val response = generate(tokens, request)
        val scope = PlanStorageScope.of(demo = false, apiBaseUrl = BuildConfig.API_BASE_URL)
        val store = AtomicFilePlanStore.open(context, scope)
        val before = (store.load() as PlanStoreLoad.Loaded).snapshot
        val plan = SessionPlan.register(before.nextOrdinal, request, response, isDemo = false)
        assertTrue(store.save(before.copy(plans = before.plans + plan, nextOrdinal = before.nextOrdinal + 1)))

        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            lateinit var viewModel: PlanFormViewModel
            activity.onActivity {
                viewModel = ViewModelProvider(it, PlanFormViewModel.Factory)[PlanFormViewModel::class.java]
            }
            withTimeout(15_000) {
                viewModel.storage.first { it == PlanStorage.Ready }
                viewModel.exportBlocks.first { viewModel.planDeletionBlockReason(plan.ordinal) == null }
            }
            activity.onActivity { assertTrue(viewModel.deletePlan(plan.ordinal)) }
            val outcome = withTimeout(15_000) {
                viewModel.planDeletion.first { it != null && it.status != PlanDeletionStatus.Deleting }
            }
            assertEquals(PlanDeletionStatus.Deleted, outcome?.status)
        }
        assertEquals(before.plans, (store.load() as PlanStoreLoad.Loaded).snapshot.plans)
        assertEquals(ReloadOutcome.Missing, FlavorRepositories.adjustmentRepository(tokens).reload(plan.scheduleCsvFile))
    }

    private fun fixtureRequest(questions: List<Question>): StudyPlanRequest {
        // Miss computer-general items (some as 모르겠음) and one spreadsheet item; answer the rest correctly.
        val answers = questions.associate { question ->
            val wrong = question.answerChoices.keys.first { it != question.correctAnswer }
            question.id to when (question.id) {
                in 6..10 -> UNKNOWN_KEY
                1, in 11..14 -> wrong
                else -> question.correctAnswer
            }
        }
        val results = questions.map { question ->
            val answer = answers.getValue(question.id)
            AssessmentResult(
                problemId = question.id,
                question = question.prompt,
                choices = question.choicesWithUnknown(),
                correctAnswer = question.correctAnswer,
                userAnswer = answer,
                isCorrect = Scoring.isCorrect(question, answer),
                possibleScore = question.possibleScore,
            )
        }
        assertEquals((1..18).toList(), results.map { it.problemId })
        assertEquals(18.0, results.sumOf { it.possibleScore }, 0.0)
        assertTrue(results.any { it.userAnswer == UNKNOWN_KEY && !it.isCorrect })
        return StudyPlanRequest(
            certificationId = CERTIFICATION_ID,
            preparationStart = "2026/10/19/18/00",
            examDate = "2026/11/30/18/00",
            selfAssessment = "스프레드시트는 익숙하지만 컴퓨터 일반은 처음입니다.",
            assessmentResults = AssessmentResults(CERTIFICATION_ID, results),
            studyDaysPerWeek = 3,
            weeklyAvailability = mapOf(
                "monday" to listOf(TimeWindow("18:00", "20:00")),
                "wednesday" to listOf(TimeWindow("18:00", "20:00")),
                "friday" to listOf(TimeWindow("18:00", "20:00")),
            ),
        )
    }

    private suspend fun generate(tokens: DeviceTokenSource, request: StudyPlanRequest): StudyPlanResponse =
        when (val outcome = FlavorRepositories.planningRepository(tokens).generate(request)) {
            is PlanOutcome.Success -> outcome.response
            is PlanOutcome.DemoShortfall -> throw AssertionError("live repository returned a demo-only shortfall")
            is PlanOutcome.Failure -> throw AssertionError("plan request failed: ${outcome.error}")
        }

    private fun readerWith(tokens: DeviceTokenSource): AdjustmentRepository = LiveAdjustmentRepository(
        BuildConfig.API_BASE_URL,
        createApiHttpClient().withDeviceCredential(BuildConfig.API_BASE_URL, tokens),
    )

    private suspend fun readBack(repository: AdjustmentRepository, identity: String) =
        when (val outcome = repository.reload(identity)) {
            is ReloadOutcome.Loaded -> outcome.schedules
            is ReloadOutcome.Missing -> throw AssertionError("saved plan $identity was not found")
            is ReloadOutcome.Failure -> throw AssertionError("saved-plan readback failed: ${outcome.error}")
        }
}
