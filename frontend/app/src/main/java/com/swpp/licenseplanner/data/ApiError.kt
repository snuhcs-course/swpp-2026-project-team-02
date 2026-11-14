package com.swpp.licenseplanner.data

/** Live failure kinds. */
sealed interface ApiError {
    /** HTTP 422: the server's `error.message`, kept as diagnostic detail; the UI shows Korean wording for it. */
    data class InvalidInput(val message: String?) : ApiError
    /** HTTP 404: wrong API URL or route. */
    data object Configuration : ApiError
    /** HTTP 404 `PLAN_NOT_FOUND` from adjustment: the selected server plan no longer exists. */
    data object PlanNotFound : ApiError
    /** HTTP 409 `PLAN_CONFLICT` from adjustment: the stored plan no longer matches the sent rows. */
    data object PlanConflict : ApiError
    /** HTTP 503: server not ready (for example key setup). */
    data class ServerUnavailable(val message: String?) : ApiError {
        var rawDiagnostic: String? = null
    }
    /** HTTP 500, 502 and any other unexpected status. */
    data class ServerFailure(val status: Int) : ApiError {
        var rawDiagnostic: String? = null
    }
    /** Connection refused, DNS failure, or timeout. */
    data object Connection : ApiError
    /** DNS lookup failed or the device has no usable route to the host. */
    data class NetworkUnavailable(val rawDiagnostic: String?) : ApiError
    /** A connect, read, or whole-request timeout. */
    data class RequestTimeout(val rawDiagnostic: String?) : ApiError
    /** The host actively refused the connection or could not be reached. */
    data class ServerUnreachable(val rawDiagnostic: String?) : ApiError
    /** An HTTP status or exception that is outside the recognized error categories. */
    data class UnknownFailure(val status: Int? = null, val rawDiagnostic: String?) : ApiError
    /** 200 with a body that cannot be parsed or validated. */
    data class InvalidResponse(val detail: String? = null) : ApiError
}
