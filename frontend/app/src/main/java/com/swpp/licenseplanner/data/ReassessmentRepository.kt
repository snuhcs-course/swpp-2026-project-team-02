package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.AssessmentResult
import com.swpp.licenseplanner.data.model.AssessmentResults
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.Question
import com.swpp.licenseplanner.domain.Reassessment
import com.swpp.licenseplanner.domain.ReassessmentFailure
import com.swpp.licenseplanner.domain.ReassessmentPreview
import com.swpp.licenseplanner.domain.Scoring
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.TopicAnswer
import java.time.LocalDateTime

/** One reassessment example question with the topic and explanation its results screen shows. */
data class ReassessmentQuestion(val question: Question, val topic: String, val explanation: String)

sealed interface ReassessmentQuestionsOutcome {
    data class Loaded(val questions: List<ReassessmentQuestion>) : ReassessmentQuestionsOutcome
    /** The server has no reassessment route or mode yet; nothing is substituted. */
    data object ServerNeedsUpdate : ReassessmentQuestionsOutcome
    data class Failure(val error: ApiError) : ReassessmentQuestionsOutcome
}

/**
 * Everything one preview or confirmation sends: the selected plan as captured, the answered set,
 * the moment from which study may change, the reported rows kept from that moment on and the first
 * fresh study ID. Reports themselves never enter the HTTP model.
 */
data class ReassessmentInput(
    val source: SessionPlan,
    val questions: List<ReassessmentQuestion>,
    val answers: Map<Int, String>,
    val replanFrom: LocalDateTime,
    val keep: Set<String> = Reassessment.keepIds(source, replanFrom),
    val firstNewStudyId: String = Reassessment.firstNewStudyId(source),
) {
    val topicAnswers: List<TopicAnswer>
        get() = questions.map { TopicAnswer(it.topic, it.question.possibleScore, Scoring.isCorrect(it.question, answers[it.question.id])) }

    fun assessmentResults(certificationId: String): AssessmentResults = AssessmentResults(certificationId, questions.map { item ->
        val question = item.question
        val answer = answers.getValue(question.id)
        AssessmentResult(question.id, question.prompt, question.choicesWithUnknown(), question.correctAnswer, answer,
            Scoring.isCorrect(question, answer), question.possibleScore)
    })
}

sealed interface ReassessmentOutcome {
    data class Previewed(val preview: ReassessmentPreview) : ReassessmentOutcome
    data class Committed(val response: StudyPlanResponse) : ReassessmentOutcome
    data class Rejected(val failure: ReassessmentFailure) : ReassessmentOutcome
    data object ServerNeedsUpdate : ReassessmentOutcome
    data class Failure(val error: ApiError) : ReassessmentOutcome
}

/** Reassessment questions, preview and confirmed update; live HTTP or the local demo, chosen by flavor. */
interface ReassessmentRepository {
    suspend fun questions(certificationId: String): ReassessmentQuestionsOutcome
    suspend fun preview(input: ReassessmentInput): ReassessmentOutcome
    /** Applies the confirmed preview; any result other than [previewed] is an invalid response. */
    suspend fun commit(input: ReassessmentInput, previewed: List<ScheduleRow>): ReassessmentOutcome
}

/** Used where no flavor repository is wired in; it never reads, sends or substitutes questions. */
object UnavailableReassessment : ReassessmentRepository {
    override suspend fun questions(certificationId: String) = ReassessmentQuestionsOutcome.Failure(ApiError.Configuration)
    override suspend fun preview(input: ReassessmentInput) = ReassessmentOutcome.Failure(ApiError.Configuration)
    override suspend fun commit(input: ReassessmentInput, previewed: List<ScheduleRow>) =
        ReassessmentOutcome.Failure(ApiError.Configuration)
}

/** Checks a returned plan against the input's re-split rules, its identity and its own CSV. */
internal fun reassessmentResponseProblem(input: ReassessmentInput, response: StudyPlanResponse): String? {
    val source = input.source
    scheduleRowProblem(response.schedules)?.let { return it }
    if (response.scheduleCsvFile != source.scheduleCsvFile) return "plan identity changed"
    val context = AvailabilityWire.context(source.original) ?: return "invalid original windows"
    if (response.availabilitySnapshot?.let { AvailabilityWire.context(it) != context } == true) return "windows changed"
    if (!Reassessment.isValidRevision(source.current.schedules, input.keep, input.replanFrom, input.firstNewStudyId, response.schedules)) {
        return "re-split rules not kept"
    }
    val topics = input.questions.map { it.topic }.toSet()
    val currentIds = source.current.schedules.map { it.scheduleId }.toSet()
    if (response.schedules.any { it.scheduleId !in currentIds && it.topic !in topics }) return "unknown topic"
    if (response.scheduleCsv.isNotEmpty() &&
        parseRemainingCsv(response.scheduleCsv)?.associateBy { it.scheduleId } != response.schedules.associateBy { it.scheduleId }) {
        return "CSV does not match rows"
    }
    return null
}
