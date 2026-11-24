package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.Reassessment
import com.swpp.licenseplanner.domain.ResplitResult
import java.time.LocalDateTime
import java.time.ZoneId

/** Bundled questions and the local re-split; no calendar, backend or provider dependency. */
class DemoReassessmentRepository(
    private val bundled: List<ReassessmentQuestion>,
    private val now: () -> LocalDateTime = { LocalDateTime.now(ZoneId.of("Asia/Seoul")).withSecond(0).withNano(0) },
) : ReassessmentRepository {
    override suspend fun questions(certificationId: String): ReassessmentQuestionsOutcome =
        ReassessmentQuestionsOutcome.Loaded(bundled)

    override suspend fun preview(input: ReassessmentInput): ReassessmentOutcome {
        val rows = when (val result = resplit(input) ?: return invalid()) {
            is ResplitResult.Failed -> return ReassessmentOutcome.Rejected(result.failure)
            is ResplitResult.Revised -> result.rows
        }
        reassessmentResponseProblem(input, StudyPlanResponse(rows, remainingCsv(rows)))?.let {
            return ReassessmentOutcome.Failure(ApiError.InvalidResponse(it))
        }
        return ReassessmentOutcome.Previewed(Reassessment.preview(input.source.current.schedules, input.keep, input.replanFrom, rows))
    }

    override suspend fun commit(input: ReassessmentInput, previewed: List<ScheduleRow>): ReassessmentOutcome {
        val rows = when (val result = resplit(input) ?: return invalid()) {
            is ResplitResult.Failed -> return ReassessmentOutcome.Rejected(result.failure)
            is ResplitResult.Revised -> result.rows
        }
        val response = StudyPlanResponse(rows, remainingCsv(rows))
        val problem = reassessmentResponseProblem(input, response)
            ?: "result differs from the preview".takeIf { rows.associateBy { it.scheduleId } != previewed.associateBy { it.scheduleId } }
        return if (problem == null) ReassessmentOutcome.Committed(response)
        else ReassessmentOutcome.Failure(ApiError.InvalidResponse(problem))
    }

    private fun resplit(input: ReassessmentInput): ResplitResult? {
        if (!input.source.isDemo || input.questions.map { it.question.id } != bundled.map { it.question.id } ||
            input.questions.any { input.answers[it.question.id] == null }) return null
        return Reassessment.resplit(input.source.current.schedules, input.keep, input.topicAnswers, input.replanFrom, now(),
            input.firstNewStudyId)
    }

    private fun invalid() = ReassessmentOutcome.Failure(ApiError.InvalidInput(null))
}
