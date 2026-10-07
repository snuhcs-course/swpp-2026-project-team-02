package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.WireDateTime
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * `POST <base>/study-plans` with the target assessment contract (GEN-01/02/04).
 * Failures are returned as-is: no legacy-payload retry and no demo fallback.
 */
class LivePlanningRepository(
    private val baseUrl: String,
    private val client: OkHttpClient = createApiHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlanningRepository {
    override suspend fun generate(request: StudyPlanRequest): PlanOutcome {
        val body = ApiJson.encodeToString(StudyPlanRequest.serializer(), request)
            .toRequestBody(JSON_MEDIA_TYPE)
        val httpRequest = Request.Builder()
            .url(apiUrl(baseUrl, "/study-plans"))
            .post(body)
            .build()
        return when (val outcome = client.executeApi(httpRequest, io)) {
            is HttpOutcome.Failed -> PlanOutcome.Failure(outcome.error)
            is HttpOutcome.Ok -> parse(outcome.body)
        }
    }

    private fun parse(body: String): PlanOutcome {
        val response = try {
            ApiJson.decodeFromString(StudyPlanResponse.serializer(), body)
        } catch (error: Exception) {
            return PlanOutcome.Failure(ApiError.InvalidResponse(error.message))
        }
        val badRow = response.schedules.firstOrNull { row ->
            val start = WireDateTime.parseOrNull(row.startDate)
            val end = WireDateTime.parseOrNull(row.endDate)
            start == null || end == null || !end.isAfter(start)
        }
        if (badRow != null) return PlanOutcome.Failure(ApiError.InvalidResponse("invalid schedule row ${badRow.scheduleId}"))
        return PlanOutcome.Success(response, isDemo = false)
    }

    companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
