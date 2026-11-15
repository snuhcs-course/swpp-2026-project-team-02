package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.RemainingStudyRequest
import com.swpp.licenseplanner.data.model.RemainingStudyResponse
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.WireDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

class LiveRemainingStudyRepository(
    private val baseUrl: String,
    client: OkHttpClient = createApiHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : RemainingStudyRepository {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()

    override suspend fun replan(input: RemainingStudyInput): AdjustmentOutcome {
        val source = input.source
        val file = source.scheduleCsvFile?.takeIf { it.isNotBlank() }
            ?: return AdjustmentOutcome.Failure(ApiError.InvalidInput("서버 계획 파일 정보가 없어요."))
        val request = RemainingStudyRequest(scheduleCsvFile = file, schedules = source.current.schedules,
            weeklyAvailability = source.original.weeklyAvailability, availabilityOverrides = source.original.availabilityOverrides,
            preparationStart = source.original.preparationStart, examDate = source.original.examDate,
            replanFrom = WireDateTime.format(input.replanFrom), remainingSessions = input.work.instructions)
        val body = OneShotBody(ApiJson.encodeToString(RemainingStudyRequest.serializer(), request)
            .toRequestBody(LivePlanningRepository.JSON_MEDIA_TYPE))
        val http = Request.Builder().url(apiUrl(baseUrl, "/schedules/adjust")).post(body).build()
        return when (val outcome = client.executeApi(http, io, planErrors = true)) {
            is HttpOutcome.Failed -> AdjustmentOutcome.Failure(outcome.error)
            is HttpOutcome.Ok -> {
                val parsed = runCatching { ApiJson.decodeFromString(RemainingStudyResponse.serializer(), outcome.body) }.getOrNull()
                    ?: return AdjustmentOutcome.Failure(ApiError.InvalidResponse("남은 공부 응답을 읽을 수 없어요."))
                val response = StudyPlanResponse(parsed.schedules, parsed.scheduleCsv, parsed.agentSummary,
                    parsed.scheduleCsvFile, parsed.availabilitySnapshot)
                val problem = if (parsed.action != "replanned_remaining") "unexpected remaining action" else remainingResponseProblem(input, response)
                if (problem == null) AdjustmentOutcome.Success(response) else AdjustmentOutcome.Failure(ApiError.InvalidResponse(problem))
            }
        }
    }

    private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        override fun isOneShot() = true
    }
}
