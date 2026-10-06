package com.swpp.licenseplanner.data

/** Live failure kinds from design D7's table (GEN-04). */
sealed interface ApiError {
    /** HTTP 422: show the server's `error.message`. */
    data class InvalidInput(val message: String?) : ApiError
    /** HTTP 404: wrong API URL or route. */
    data object Configuration : ApiError
    /** HTTP 503: server not ready (for example key setup). */
    data class ServerUnavailable(val message: String?) : ApiError
    /** HTTP 500, 502 and any other unexpected status. */
    data class ServerFailure(val status: Int) : ApiError
    /** Connection refused, DNS failure, or timeout. */
    data object Connection : ApiError
    /** 200 with a body that cannot be parsed or validated. */
    data class InvalidResponse(val detail: String? = null) : ApiError
}
