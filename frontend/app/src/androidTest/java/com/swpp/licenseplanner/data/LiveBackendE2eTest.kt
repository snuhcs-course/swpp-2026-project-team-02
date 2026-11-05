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

/**
 * Minimal Android live-flavor E2E path.
 *
 * The test intentionally targets the checked-in Python fixed fixture running
 * on the host at 10.0.2.2:8000. It exercises Android's real OkHttp transport
 * and serializers, then the production Python handler, scheduler, and plan
 * store. No OpenRouter request is made by the fixture.
 */
@RunWith(AndroidJUnit4::class)
class LiveBackendE2eTest {
    @Test
    fun loadsQuestions_generatesPlan_andReadsSavedPlanFromFixedBackend() = runBlocking {
        assertFalse("Run this test with connectedLiveDebugAndroidTest", BuildConfig.DEMO_MODE)
        assertEquals("http://10.0.2.2:8000/api/v1", BuildConfig.API_BASE_URL)

        val questions = when (val outcome = LiveQuestionRepository(BuildConfig.API_BASE_URL).load(CERTIFICATION_ID)) {
            is QuestionOutcome.Loaded -> outcome.questions
            is QuestionOutcome.Failure -> throw AssertionError("question request failed: ${outcome.error}")
        }
        assertEquals(5, questions.size)

        val answers = mapOf(1 to "A", 2 to "B", 3 to "A", 4 to "A", 5 to "A")
        val request = StudyPlanRequest(
            certificationId = CERTIFICATION_ID,
            preparationStart = "2026/10/19/18/00",
            examDate = "2026/11/02/18/00",
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

        val reloaded = when (val outcome = LiveAdjustmentRepository(BuildConfig.API_BASE_URL).reload(identity)) {
            is ReloadOutcome.Loaded -> outcome.schedules
            is ReloadOutcome.Missing -> throw AssertionError("generated plan was not saved")
            is ReloadOutcome.Failure -> throw AssertionError("saved-plan readback failed: ${outcome.error}")
        }
        assertEquals(plan.schedules, reloaded)
    }
}
