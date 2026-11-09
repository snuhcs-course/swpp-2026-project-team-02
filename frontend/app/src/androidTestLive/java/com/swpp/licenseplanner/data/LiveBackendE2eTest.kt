package com.swpp.licenseplanner.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.licenseplanner.BuildConfig
import com.swpp.licenseplanner.data.model.AssessmentResult
import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.Scoring
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Minimal Android live-flavor E2E path.
 *
 * The test intentionally targets the checked-in Python fixed fixture through
 * the live-flavor BuildConfig URL. It exercises Android's real OkHttp transport
 * and serializers, then the production Python handler, scheduler, adjustment
 * agent loop with fixed model replies, stored exam deadline, and plan store.
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
    fun loadsQuestions_generatesPlan_adjustsIt_andReadsSavedPlanFromFixedBackend() = runBlocking {
        assertFalse("Run this test with connectedLiveDebugAndroidTest", BuildConfig.DEMO_MODE)
        assertTrue("live API base URL must be configured", BuildConfig.API_BASE_URL.isNotBlank())

        val questions = when (val outcome = LiveQuestionRepository(BuildConfig.API_BASE_URL).load(CERTIFICATION_ID)) {
            is QuestionOutcome.Loaded -> outcome.questions
            is QuestionOutcome.Failure -> throw AssertionError("question request failed: ${outcome.error}")
        }
        assertEquals((1..18).toList(), questions.map { it.id })

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
        val request = StudyPlanRequest(
            certificationId = CERTIFICATION_ID,
            preparationStart = "2026/10/19/18/00",
            // Later than the fixture trace's sessions so a study session can still move before the exam.
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
        val plan = when (val outcome = LivePlanningRepository(BuildConfig.API_BASE_URL).generate(request)) {
            is PlanOutcome.Success -> outcome.response
            is PlanOutcome.DemoShortfall -> throw AssertionError("live repository returned a demo-only shortfall")
            is PlanOutcome.Failure -> throw AssertionError("plan request failed: ${outcome.error}")
        }
        assertTrue(plan.schedules.isNotEmpty())
        // The backend's reviewed topics for both written subjects.
        val computerGeneral = setOf("컴퓨터 시스템 활용", "인터넷 자료 활용", "컴퓨터 시스템 보호")
        val spreadsheet = setOf("스프레드시트 기본", "함수와 데이터 관리", "차트와 분석", "페이지 설정과 인쇄", "매크로 활용")
        assertEquals(computerGeneral + spreadsheet, plan.schedules.map { it.topic }.filter { it.isNotEmpty() }.toSet())
        val identity = requireNotNull(plan.scheduleCsvFile) { "fixture must return a saved-plan identity" }

        val adjustments = LiveAdjustmentRepository(BuildConfig.API_BASE_URL)
        assertEquals(plan.schedules, readBack(adjustments, identity))

        val target = plan.schedules.first { it.scheduleId.startsWith("study-") }
        val adjusted = when (val outcome = adjustments.adjust(AdjustmentInput(request, identity, plan.schedules, target.scheduleId))) {
            is AdjustmentOutcome.Success -> outcome.response.schedules
            AdjustmentOutcome.DemoNoSlot -> throw AssertionError("live repository returned a demo-only result")
            is AdjustmentOutcome.Failure -> throw AssertionError("adjustment failed: ${outcome.error}")
        }
        val moved = adjusted.single { it.scheduleId == target.scheduleId }
        val start = LocalDateTime.parse(moved.startDate, wire)
        val end = LocalDateTime.parse(moved.endDate, wire)
        assertTrue("the session must move later", start > LocalDateTime.parse(target.startDate, wire))
        assertTrue("the whole session must end by the original exam", !end.isAfter(LocalDateTime.parse(request.examDate, wire)))
        assertEquals(target.topic, moved.topic)
        assertEquals(
            plan.schedules.filter { it.scheduleId != target.scheduleId },
            adjusted.filter { it.scheduleId != target.scheduleId },
        )
        assertEquals(adjusted, readBack(adjustments, identity))
    }

    private suspend fun readBack(repository: LiveAdjustmentRepository, identity: String) =
        when (val outcome = repository.reload(identity)) {
            is ReloadOutcome.Loaded -> outcome.schedules
            is ReloadOutcome.Missing -> throw AssertionError("saved plan $identity was not found")
            is ReloadOutcome.Failure -> throw AssertionError("saved-plan readback failed: ${outcome.error}")
        }
}
