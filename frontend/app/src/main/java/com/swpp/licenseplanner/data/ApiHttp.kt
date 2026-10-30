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

/** Executes [request] off the main thread and maps non-2xx statuses and I/O errors. */
internal suspend fun OkHttpClient.executeApi(request: Request, io: CoroutineDispatcher = Dispatchers.IO): HttpOutcome =
    withContext(io) {
        try {
            newCall(request).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) HttpOutcome.Ok(body)
                else HttpOutcome.Failed(errorForStatus(response.code, body))
            }
        } catch (_: IOException) {
            HttpOutcome.Failed(ApiError.Connection)
        }
    }

internal fun errorForStatus(status: Int, body: String): ApiError {
    val error = try {
        ApiJson.decodeFromString(ApiErrorEnvelope.serializer(), body).error
    } catch (_: Exception) {
        null // Lenient: fall back to a generic message.
    }
    val message = error?.message?.takeIf { it.isNotBlank() }
    return when (status) {
        422 -> ApiError.InvalidInput(message)
        404 -> if (error?.code == "PLAN_NOT_FOUND") ApiError.InvalidInput("서버에서 이 계획을 찾을 수 없어요. 새 계획을 만들어 주세요.") else ApiError.Configuration
        503 -> ApiError.ServerUnavailable(message)
        else -> ApiError.ServerFailure(status)
    }
}
