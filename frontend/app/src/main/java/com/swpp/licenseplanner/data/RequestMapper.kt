package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.AssessmentResult
import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.TimeWindow
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.Scoring
import com.swpp.licenseplanner.domain.Validation
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WireDateTime

sealed interface MappingResult {
    data class Ready(val request: StudyPlanRequest) : MappingResult
    /** The form is incomplete; generation stays blocked. */
    data class Incomplete(val reason: String) : MappingResult
}

/** Converts the form into the target `POST /study-plans` request (GEN-01, design D5). Pure. */
object RequestMapper {
    fun map(state: PlanFormState): MappingResult {
        val certificationId = state.certificationId ?: return MappingResult.Incomplete("certification not selected")
        val examDate = state.examDate ?: return MappingResult.Incomplete("exam date missing")
        if (state.datesError != null) return MappingResult.Incomplete("dates are invalid")
        val level = state.level ?: return MappingResult.Incomplete("level not selected")
        if (state.questions.isEmpty()) return MappingResult.Incomplete("questions not loaded")
        if (!state.allAnswered) return MappingResult.Incomplete("assessment incomplete")
        if (state.availabilityError != null) return MappingResult.Incomplete("availability invalid")

        val windows = LinkedHashMap<String, List<TimeWindow>>()
        for (day in Weekday.entries) {
            val entry = state.availability[day] ?: continue
            if (!entry.enabled) continue
            val window = Validation.window(entry) ?: return MappingResult.Incomplete("availability invalid")
            windows[day.key] = listOf(TimeWindow(WireDateTime.formatTime(window.start), WireDateTime.formatTime(window.end)))
        }

        val results = state.questions.map { question ->
            val answer = state.answers.getValue(question.id)
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

        return MappingResult.Ready(
            StudyPlanRequest(
                certificationId = certificationId,
                preparationStart = WireDateTime.formatDate(state.preparationStart),
                examDate = WireDateTime.formatDate(examDate),
                selfAssessment = level.selfAssessment,
                assessmentResults = AssessmentResults(certificationId, results),
                studyDaysPerWeek = windows.size,
                weeklyAvailability = windows,
                busySchedules = emptyList(),
            ),
        )
    }
}
