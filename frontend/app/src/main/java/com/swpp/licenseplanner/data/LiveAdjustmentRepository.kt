package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleAdjustmentRequest
import com.swpp.licenseplanner.data.model.ScheduleAdjustmentResponse
import com.swpp.licenseplanner.data.model.StudyPlanListResponse
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * `POST <base>/schedules/adjust` with a four-field body, and
 * `GET <base>/study-plans?schedule_csv_file=<identity>` to read one plan back. Responses are validated before the caller touches its plan; failures are
 * returned as-is, with no retry and no demo fallback. Success does not prove the server saved the file.
 */
class LiveAdjustmentRepository(
    private val baseUrl: String,
    private val client: OkHttpClient = createApiHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AdjustmentRepository {
    override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
        // The ViewModel never submits a live plan without identity; never fabricate one.
        val file = input.scheduleCsvFile?.takeIf { it.isNotBlank() }
            ?: return AdjustmentOutcome.Failure(ApiError.InvalidInput(MISSING_IDENTITY))
        val request = ScheduleAdjustmentRequest(
            scheduleCsvFile = file,
            scheduleId = input.scheduleId,
            schedules = input.schedules,
            weeklyAvailability = input.original.weeklyAvailability,
        )
        val body = OneShotBody(
            ApiJson.encodeToString(ScheduleAdjustmentRequest.serializer(), request)
                .toRequestBody(LivePlanningRepository.JSON_MEDIA_TYPE),
        )
        val httpRequest = Request.Builder()
            .url(apiUrl(baseUrl, "/schedules/adjust"))
            .post(body)
            .build()
        return when (val outcome = client.executeApi(httpRequest, io, planErrors = true)) {
            is HttpOutcome.Failed -> AdjustmentOutcome.Failure(outcome.error)
            is HttpOutcome.Ok -> parse(outcome.body, request)
        }
    }

    private fun parse(body: String, request: ScheduleAdjustmentRequest): AdjustmentOutcome {
        val response = try {
            ApiJson.decodeFromString(ScheduleAdjustmentResponse.serializer(), body)
        } catch (error: Exception) {
            return AdjustmentOutcome.Failure(ApiError.InvalidResponse(error.message))
        }
        val problem = problemWith(response, request)
        if (problem != null) return AdjustmentOutcome.Failure(ApiError.InvalidResponse(problem))
        return AdjustmentOutcome.Success(
            StudyPlanResponse(
                schedules = response.schedules,
                scheduleCsv = response.scheduleCsv,
                agentSummary = response.agentSummary,
                scheduleCsvFile = request.scheduleCsvFile,
            ),
        )
    }

    /** Response checks; null when the response may replace the plan. */
    private fun problemWith(response: ScheduleAdjustmentResponse, request: ScheduleAdjustmentRequest): String? {
        if (response.action != RESCHEDULED_STUDY) return "unexpected action ${response.action}"
        if (response.targetScheduleId != request.scheduleId) return "target ${response.targetScheduleId} was not requested"
        if (response.scheduleCsvFile != null && response.scheduleCsvFile != request.scheduleCsvFile) {
            return "schedule_csv_file does not match the requested plan"
        }
        scheduleRowProblem(response.schedules)?.let { return it }
        if (response.schedules.none { it.scheduleId == request.scheduleId }) return "target row ${request.scheduleId} is missing"
        return null
    }

    /**
     * Asks for the plan list filtered to [scheduleCsvFile], so the server reads only that plan, and
     * keeps only the item whose `schedule_csv_file` equals it exactly; other plans are never returned.
     * A server that ignores the filter returns the full list, which goes through the same match.
     * A route 404 is a configuration error, not a missing plan.
     */
    override suspend fun reload(scheduleCsvFile: String?): ReloadOutcome {
        val file = scheduleCsvFile?.takeIf { it.isNotBlank() }
            ?: return ReloadOutcome.Failure(ApiError.InvalidInput(MISSING_IDENTITY))
        val url = apiUrl(baseUrl, "/study-plans").toHttpUrl().newBuilder()
            .addQueryParameter("schedule_csv_file", file)
            .build()
        val httpRequest = Request.Builder()
            .url(url)
            .get()
            .build()
        return when (val outcome = client.executeApi(httpRequest, io)) {
            is HttpOutcome.Failed -> ReloadOutcome.Failure(outcome.error)
            is HttpOutcome.Ok -> parseList(outcome.body, file)
        }
    }

    private fun parseList(body: String, file: String): ReloadOutcome {
        val list = try {
            ApiJson.decodeFromString(StudyPlanListResponse.serializer(), body)
        } catch (error: Exception) {
            return ReloadOutcome.Failure(ApiError.InvalidResponse(error.message))
        }
        val matches = list.plans.filter { it.scheduleCsvFile == file }
        if (matches.isEmpty()) return ReloadOutcome.Missing
        if (matches.size > 1) return ReloadOutcome.Failure(ApiError.InvalidResponse("duplicate schedule_csv_file"))
        val rows = matches.single().schedules
        scheduleRowProblem(rows)?.let { return ReloadOutcome.Failure(ApiError.InvalidResponse(it)) }
        return ReloadOutcome.Loaded(rows)
    }

    /**
     * Stops OkHttp from replaying the adjustment by itself (408/503 `Retry-After: 0` follow-ups,
     * redirects, recovery after sending); only an explicit retry may send it again.
     */
    private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        override fun isOneShot() = true
    }

    companion object {
        const val RESCHEDULED_STUDY = "rescheduled_study"
        private const val MISSING_IDENTITY = "이 계획에는 서버 계획 파일 정보가 없어 조정할 수 없어요."
    }
}
