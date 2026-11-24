package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ReassessmentRequest
import com.swpp.licenseplanner.data.model.ReassessmentResponse
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.Reassessment
import com.swpp.licenseplanner.domain.ReassessmentFailure
import com.swpp.licenseplanner.domain.WireDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * `GET <base>/certifications/{id}/reassessment-questions` and `POST <base>/schedules/adjust` in
 * reassessment mode. A server without the route or mode is reported as needing an update; failures
 * never fall back to demo questions or a local re-split.
 */
class LiveReassessmentRepository(
    private val baseUrl: String,
    client: OkHttpClient = createApiHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ReassessmentRepository {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    override suspend fun questions(certificationId: String): ReassessmentQuestionsOutcome {
        val request = Request.Builder().url(apiUrl(baseUrl, "/certifications/$certificationId/reassessment-questions")).get().build()
        return when (val outcome = client.executeApi(request, io)) {
            is HttpOutcome.Failed -> if (outcome.error == ApiError.Configuration) ReassessmentQuestionsOutcome.ServerNeedsUpdate
                else ReassessmentQuestionsOutcome.Failure(outcome.error)
            is HttpOutcome.Ok -> try {
                ReassessmentQuestionsOutcome.Loaded(parseQuestions(outcome.body, certificationId))
            } catch (error: InvalidQuestionDataException) {
                ReassessmentQuestionsOutcome.Failure(ApiError.InvalidResponse(error.message))
            }
        }
    }

    override suspend fun preview(input: ReassessmentInput): ReassessmentOutcome =
        send(input, commit = false, previewed = null)

    override suspend fun commit(input: ReassessmentInput, previewed: List<ScheduleRow>): ReassessmentOutcome =
        send(input, commit = true, previewed = previewed)

    private suspend fun send(input: ReassessmentInput, commit: Boolean, previewed: List<ScheduleRow>?): ReassessmentOutcome {
        val source = input.source
        val file = source.scheduleCsvFile?.takeIf { it.isNotBlank() }
            ?: return ReassessmentOutcome.Failure(ApiError.InvalidInput("서버 계획 파일 정보가 없어요."))
        val request = ReassessmentRequest(commit = commit, scheduleCsvFile = file, schedules = source.current.schedules,
            weeklyAvailability = source.original.weeklyAvailability, availabilityOverrides = source.original.availabilityOverrides,
            preparationStart = source.original.preparationStart, examDate = source.original.examDate,
            replanFrom = WireDateTime.format(input.replanFrom), keepScheduleIds = input.keep.sorted(),
            firstNewStudyId = input.firstNewStudyId, assessmentResults = input.assessmentResults(source.original.certificationId))
        val body = OneShotBody(ApiJson.encodeToString(ReassessmentRequest.serializer(), request)
            .toRequestBody(LivePlanningRepository.JSON_MEDIA_TYPE))
        val http = Request.Builder().url(apiUrl(baseUrl, "/schedules/adjust")).post(body).build()
        return when (val outcome = client.executeApi(http, io, planErrors = true)) {
            is HttpOutcome.Failed -> failure(outcome.error)
            is HttpOutcome.Ok -> {
                val parsed = runCatching { ApiJson.decodeFromString(ReassessmentResponse.serializer(), outcome.body) }.getOrNull()
                    ?: return ReassessmentOutcome.Failure(ApiError.InvalidResponse("다시 진단 응답을 읽을 수 없어요."))
                val response = StudyPlanResponse(parsed.schedules, parsed.scheduleCsv, parsed.agentSummary,
                    parsed.scheduleCsvFile, parsed.availabilitySnapshot)
                val problem = when {
                    parsed.action != if (commit) "reassessed" else "reassessment_preview" -> "unexpected reassessment action"
                    else -> reassessmentResponseProblem(input, response) ?: "result differs from the preview".takeIf {
                        previewed != null && previewed.associateBy { it.scheduleId } != response.schedules.associateBy { it.scheduleId }
                    }
                }
                when {
                    problem != null -> ReassessmentOutcome.Failure(ApiError.InvalidResponse(problem))
                    commit -> ReassessmentOutcome.Committed(response)
                    else -> ReassessmentOutcome.Previewed(Reassessment.preview(source.current.schedules, input.keep,
                        input.replanFrom, response.schedules))
                }
            }
        }
    }

    private fun failure(error: ApiError): ReassessmentOutcome {
        val message = (error as? ApiError.InvalidInput)?.message.orEmpty()
        return when {
            error == ApiError.ReassessmentPreviewExpired -> ReassessmentOutcome.Rejected(ReassessmentFailure.PreviewExpired)
            message.startsWith("NOT_ENOUGH_TIME") -> ReassessmentOutcome.Rejected(ReassessmentFailure.NotEnoughTime)
            message.startsWith("mode must be") -> ReassessmentOutcome.ServerNeedsUpdate
            else -> ReassessmentOutcome.Failure(error)
        }
    }

    /** The onboarding question checks, plus a non-blank topic and explanation for every item. */
    private fun parseQuestions(body: String, certificationId: String): List<ReassessmentQuestion> {
        val questions = QuestionAdapter.parse(body, certificationId)
        val items = runCatching { ApiJson.parseToJsonElement(body).jsonObject.getValue("questions").jsonArray }.getOrNull()
            ?: throw InvalidQuestionDataException("questions must be an array")
        return questions.zip(items) { question, item ->
            fun text(key: String): String = ((item as? JsonObject)?.get(key) as? JsonPrimitive)
                ?.takeIf { it.isString && it.content.isNotBlank() }?.content
                ?: throw InvalidQuestionDataException("$key is missing for id ${question.id}")
            ReassessmentQuestion(question, text("topic"), text("explanation"))
        }
    }

    private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        override fun isOneShot() = true
    }
}
