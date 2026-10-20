package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ApiErrorEnvelope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OkHttp client with timeouts of 10 s connect, 120 s read and whole call (Gemini latency). */
fun createApiHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .callTimeout(120, TimeUnit.SECONDS)
    .build()

/** Joins the configured base URL and an API path without doubling slashes. */
fun apiUrl(baseUrl: String, path: String): String = baseUrl.trimEnd('/') + path

/** Result of one HTTP exchange before body interpretation. */
internal sealed interface HttpOutcome {
    data class Ok(val body: String) : HttpOutcome
    data class Failed(val error: ApiError) : HttpOutcome
}

/**
 * Executes [request] off the main thread and maps non-2xx statuses and I/O errors.
 * [planErrors] enables the stored-plan codes, which only the adjustment route returns.
 */
internal suspend fun OkHttpClient.executeApi(
    request: Request,
    io: CoroutineDispatcher = Dispatchers.IO,
    planErrors: Boolean = false,
): HttpOutcome =
    withContext(io) {
        try {
            newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) HttpOutcome.Ok(body)
                else HttpOutcome.Failed(errorForStatus(response.code, body, planErrors))
            }
        } catch (_: IOException) {
            HttpOutcome.Failed(ApiError.Connection)
        }
    }

internal fun errorForStatus(status: Int, body: String, planErrors: Boolean = false): ApiError {
    val error = try {
        ApiJson.decodeFromString(ApiErrorEnvelope.serializer(), body).error
    } catch (_: Exception) {
        null // Lenient: fall back to a generic message.
    }
    val message = error?.message?.takeIf { it.isNotBlank() }
    // Only the exact status/code pairs; anything else keeps its generic category.
    return when {
        planErrors && status == 404 && error?.code == PLAN_NOT_FOUND -> ApiError.PlanNotFound
        planErrors && status == 409 && error?.code == PLAN_CONFLICT -> ApiError.PlanConflict
        else -> genericError(status, message)
    }
}

private const val PLAN_NOT_FOUND = "PLAN_NOT_FOUND"
private const val PLAN_CONFLICT = "PLAN_CONFLICT"

private fun genericError(status: Int, message: String?): ApiError =
    when (status) {
        422 -> ApiError.InvalidInput(message)
        404 -> ApiError.Configuration
        503 -> ApiError.ServerUnavailable(message)
        else -> ApiError.ServerFailure(status)
    }
