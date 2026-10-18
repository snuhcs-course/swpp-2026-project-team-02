package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ScheduleAdjustmentRequest
import com.swpp.licenseplanner.data.model.ScheduleAdjustmentResponse
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.StudyPlanListResponse
import com.swpp.licenseplanner.domain.WireDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink

/**
 * `POST <base>/schedules/adjust` with a four-field body. Responses are
 * validated before the caller touches its plan; failures are returned as-is, with no retry
 * and no demo fallback. After a failure, read the saved plan before another mutation.
 */
class LiveAdjustmentRepository(
    private val baseUrl: String,
    private val client: OkHttpClient = createApiHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AdjustmentRepository {
    private val needsRefresh = mutableSetOf<String>()

    override suspend fun adjust(input: AdjustmentInput): AdjustmentOutcome {
        // The ViewModel never submits a live plan without identity; never fabricate one.
        val file = input.scheduleCsvFile?.takeIf { it.isNotBlank() }
            ?: return AdjustmentOutcome.Failure(ApiError.InvalidInput(MISSING_IDENTITY))
        if (file in needsRefresh) {
            val read = Request.Builder().url(apiUrl(baseUrl, "/study-plans")).get().build()
            val saved = when (val result = client.executeApi(read, io)) {
                is HttpOutcome.Failed -> return AdjustmentOutcome.Failure(result.error)
                is HttpOutcome.Ok -> try {
                    ApiJson.decodeFromString(StudyPlanListResponse.serializer(), result.body)
                        .plans.singleOrNull { it.scheduleCsvFile == file }
                        ?: return AdjustmentOutcome.Failure(ApiError.InvalidInput("서버에서 이 계획을 찾을 수 없어요. 새 계획을 만들어 주세요."))
                } catch (error: Exception) {
                    return AdjustmentOutcome.Failure(ApiError.InvalidResponse(error.message))
                }
            }
            val ids = saved.schedules.map { it.scheduleId }
            if (ids.any { it.isBlank() } || ids.distinct().size != ids.size || saved.schedules.any {
                val start = WireDateTime.parseOrNull(it.startDate)
                val end = WireDateTime.parseOrNull(it.endDate)
                start == null || end == null || !end.isAfter(start)
            }) return AdjustmentOutcome.Failure(ApiError.InvalidResponse("invalid saved plan"))
            needsRefresh.remove(file)
            if (saved.schedules.toSet() != input.schedules.toSet()) {
                return AdjustmentOutcome.Refreshed(saved)
            }
        }
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
        needsRefresh.add(file)
        val result = when (val outcome = client.executeApi(httpRequest, io)) {
            is HttpOutcome.Failed -> AdjustmentOutcome.Failure(outcome.error)
            is HttpOutcome.Ok -> parse(outcome.body, request)
        }
        if (result is AdjustmentOutcome.Success) needsRefresh.remove(file)
        return result
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
        val ids = response.schedules.map { it.scheduleId }
        if (ids.any { it.isBlank() }) return "blank schedule_id"
        if (ids.toSet().size != ids.size) return "duplicate schedule_id"
        if (request.scheduleId !in ids) return "target row ${request.scheduleId} is missing"
        val badRow = response.schedules.firstOrNull { row ->
            val start = WireDateTime.parseOrNull(row.startDate)
            val end = WireDateTime.parseOrNull(row.endDate)
            start == null || end == null || !end.isAfter(start)
        }
        return badRow?.let { "invalid schedule row ${it.scheduleId}" }
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
