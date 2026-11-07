package com.swpp.licenseplanner.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.swpp.licenseplanner.BuildConfig
import com.swpp.licenseplanner.data.model.AssessmentResult
import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
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
        assertEquals(5, questions.size)

        val answers = mapOf(1 to "A", 2 to "B", 3 to "A", 4 to "A", 5 to "A")
        val request = StudyPlanRequest(
            certificationId = CERTIFICATION_ID,
            preparationStart = "2026/10/19/18/00",
            // Later than the recorded trace's sessions so a study session can still move before the exam.
            examDate = "2026/11/30/18/00",
            selfAssessment = "기본 기능은 사용해 보았고 시험 준비를 시작합니다.",
            assessmentResults = AssessmentResults(
                CERTIFICATION_ID,
                questions.map { question ->
                    AssessmentResult(
                        problemId = question.id,
                        question = question.prompt,
                        choices = question.choices,
                        correctAnswer = question.correctAnswer,
                        userAnswer = answers.getValue(question.id),
                        isCorrect = answers.getValue(question.id) == question.correctAnswer,
                        possibleScore = question.possibleScore,
                    )
                },
            ),
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
