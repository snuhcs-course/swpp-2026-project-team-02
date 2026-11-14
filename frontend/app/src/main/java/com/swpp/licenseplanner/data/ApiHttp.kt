package com.swpp.licenseplanner.data

import com.swpp.licenseplanner.data.model.ApiErrorEnvelope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
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
 *
 * Every exchange asks for `Connection: close`: the API server closes the socket after each
 * response without saying so, and a pooled connection reused shortly afterwards fails on send,
 * which a one-shot adjustment body cannot recover from.
 */
internal suspend fun OkHttpClient.executeApi(
    request: Request,
    io: CoroutineDispatcher = Dispatchers.IO,
    planErrors: Boolean = false,
): HttpOutcome =
    withContext(io) {
        try {
            newCall(request.newBuilder().header("Connection", "close").build()).execute().use { response ->
                val body = response.body.string()
                if (response.isSuccessful) HttpOutcome.Ok(body)
                else HttpOutcome.Failed(errorForStatus(response.code, body, planErrors))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            HttpOutcome.Failed(classifyTransportError(error))
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
        else -> genericError(status, message, body)
    }
}

private const val PLAN_NOT_FOUND = "PLAN_NOT_FOUND"
private const val PLAN_CONFLICT = "PLAN_CONFLICT"

private fun genericError(status: Int, message: String?, body: String): ApiError =
    when (status) {
        408, 504 -> ApiError.RequestTimeout("HTTP $status\n${responseDiagnostic(message, body)}")
        422 -> ApiError.InvalidInput(message ?: responseDiagnostic(message, body))
        404 -> ApiError.Configuration
        503 -> ApiError.ServerUnavailable(message).also { it.rawDiagnostic = responseDiagnostic(message, body) }
        in 500..599 -> ApiError.ServerFailure(status).also { it.rawDiagnostic = responseDiagnostic(message, body) }
        else -> ApiError.UnknownFailure(status, responseDiagnostic(message, body))
    }

private fun classifyTransportError(error: Exception): ApiError {
    val diagnostic = "${error.javaClass.name}: ${error.message.orEmpty()}".take(MAX_DIAGNOSTIC_LENGTH)
    return when (error) {
        is SocketTimeoutException -> ApiError.RequestTimeout(diagnostic)
        is UnknownHostException, is NoRouteToHostException -> ApiError.NetworkUnavailable(diagnostic)
        is ConnectException -> ApiError.ServerUnreachable(diagnostic)
        is IOException -> ApiError.UnknownFailure(rawDiagnostic = diagnostic)
        else -> ApiError.UnknownFailure(rawDiagnostic = diagnostic)
    }
}

private fun responseDiagnostic(message: String?, body: String): String =
    (message?.takeIf { it.isNotBlank() } ?: body.trim().ifBlank { "No response body" })
        .take(MAX_DIAGNOSTIC_LENGTH)

private const val MAX_DIAGNOSTIC_LENGTH = 4_000
