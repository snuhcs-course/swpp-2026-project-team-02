package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.AvailabilitySnapshot
import com.swpp.licenseplanner.domain.StudyAvailability
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface ProfileOutcome {
    /** The validated profile: base week and every week replacement, including unshown windows. */
    data class Loaded(val availability: StudyAvailability) : ProfileOutcome
    data class Failure(val error: ApiError) : ProfileOutcome
}

/**
 * The server's single shared, unauthenticated study-time profile. It is read or replaced only on
 * the learner's explicit request; generation, navigation and restored plans never call it.
 */
interface AvailabilityProfileRepository {
    suspend fun load(): ProfileOutcome

    /** Replaces the whole profile with [availability], including an empty replacement map. */
    suspend fun save(availability: StudyAvailability): ProfileOutcome
}

/** `GET`/`PUT <base>/availability`. Responses are validated; failures are returned as-is, never retried. */
class LiveAvailabilityProfileRepository(
    private val baseUrl: String,
    private val client: OkHttpClient = createApiHttpClient(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : AvailabilityProfileRepository {
    override suspend fun load(): ProfileOutcome {
        val request = Request.Builder().url(apiUrl(baseUrl, "/availability")).get().build()
        return when (val outcome = client.executeApi(request, io)) {
            is HttpOutcome.Failed -> ProfileOutcome.Failure(outcome.error)
            is HttpOutcome.Ok -> parse(outcome.body, expected = null)
        }
    }

    override suspend fun save(availability: StudyAvailability): ProfileOutcome {
        val body = AvailabilitySnapshot(AvailabilityWire.week(availability.base), AvailabilityWire.overrides(availability.overrides))
        val request = Request.Builder()
            .url(apiUrl(baseUrl, "/availability"))
            .put(ApiJson.encodeToString(AvailabilitySnapshot.serializer(), body).toRequestBody(LivePlanningRepository.JSON_MEDIA_TYPE))
            .build()
        return when (val outcome = client.executeApi(request, io)) {
            is HttpOutcome.Failed -> ProfileOutcome.Failure(outcome.error)
            is HttpOutcome.Ok -> parse(outcome.body, expected = availability)
        }
    }

    /** A saved profile must come back exactly as sent; a read accepts either stored window form. */
    private fun parse(body: String, expected: StudyAvailability?): ProfileOutcome {
        val snapshot = try {
            ApiJson.decodeFromString(AvailabilitySnapshot.serializer(), body)
        } catch (error: Exception) {
            return ProfileOutcome.Failure(ApiError.InvalidResponse(error.message))
        }
        val availability = AvailabilityWire.context(snapshot)
            ?: return ProfileOutcome.Failure(ApiError.InvalidResponse("invalid availability profile"))
        if (expected != null && availability != expected) {
            return ProfileOutcome.Failure(ApiError.InvalidResponse("saved profile differs from the request"))
        }
        return ProfileOutcome.Loaded(availability)
    }
}
