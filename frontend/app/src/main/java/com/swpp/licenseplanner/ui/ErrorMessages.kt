package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.data.ApiError

/** User-facing text for the error table in the README. */
fun ApiError.userMessage(): String = when (this) {
    is ApiError.InvalidInput -> invalidInputMessage(message)
    ApiError.Configuration -> "연결 또는 설정 문제가 있어요. API 주소를 확인해 주세요. (HTTP 404)"
    ApiError.PlanNotFound -> PLAN_NOT_FOUND_MESSAGE
    ApiError.PlanConflict -> PLAN_CONFLICT_MESSAGE
    is ApiError.ServerUnavailable -> "서버가 아직 준비되지 않았어요. 잠시 후 다시 시도해 주세요. (HTTP 503)"
    is ApiError.ServerFailure -> "서버에서 요청을 처리하지 못했어요. 다시 시도해 주세요. (HTTP $status)"
    ApiError.Connection -> "서버에 연결할 수 없어요. 네트워크와 서버 상태를 확인한 뒤 다시 시도해 주세요."
    is ApiError.InvalidResponse -> "서버 응답을 읽을 수 없어요. 다시 시도해 주세요."
}

/**
 * Whether a live adjustment failure leaves the stored plan unknown, so only reading the latest
 * saved plan (never resending the mutation) may follow: any 409, a lost connection or timeout,
 * or a 200 that could not be read or did not match the request.
 */
fun ApiError.requiresReload(): Boolean = when (this) {
    ApiError.PlanConflict, ApiError.Connection, is ApiError.InvalidResponse -> true
    is ApiError.ServerFailure -> status == 409
    else -> false
}

/**
 * Adjustment wording. An uncertain result never claims the server applied or undid anything,
 * and a route 404 is not reported as a deleted plan.
 */
fun ApiError.adjustmentMessage(): String = when {
    this == ApiError.Configuration -> ROUTE_NOT_FOUND_MESSAGE
    this == ApiError.PlanConflict -> PLAN_CONFLICT_MESSAGE
    requiresReload() -> "조정 결과를 확인할 수 없어요. 서버에 반영되었을 수 있으니 최신 계획을 불러와 확인해 주세요."
    else -> userMessage()
}

/** Latest-plan reload failure; the shown plan is still the earlier local copy. */
fun ApiError.reloadMessage(): String = "화면에는 이전 계획이 남아 있어요. " + when (this) {
    ApiError.Configuration -> ROUTE_NOT_FOUND_MESSAGE
    else -> userMessage()
}

/**
 * A 422 shows the server's explanation, except that the backend's `NOT_ENOUGH_TIME:` code is
 * replaced by app wording; the server's capacity and advice text after the code is kept.
 */
private fun invalidInputMessage(message: String?): String {
    if (message.isNullOrBlank()) return "입력값을 확인해 주세요."
    if (!message.startsWith(NOT_ENOUGH_TIME_CODE)) return message
    val detail = message.removePrefix(NOT_ENOUGH_TIME_CODE).trim()
        .ifEmpty { "공부 가능 시간을 늘리거나, 준비를 더 일찍 시작하거나, 시험일을 늦춰 주세요." }
    return "시험일 전까지 공부할 시간이 부족해요.\n$detail"
}

private const val NOT_ENOUGH_TIME_CODE = "NOT_ENOUGH_TIME:"
private const val PLAN_NOT_FOUND_MESSAGE =
    "서버에서 이 계획을 찾을 수 없어요. 화면에는 이전 계획이 남아 있어요. 목록에서 다른 계획을 고르거나 새 계획을 만들어 주세요."
private const val PLAN_CONFLICT_MESSAGE = "서버의 계획이 변경되었어요. 최신 계획을 불러온 뒤 일정을 다시 선택해 주세요."
private const val ROUTE_NOT_FOUND_MESSAGE = "API 경로를 찾을 수 없어요. API 주소와 서버 버전을 확인해 주세요. (HTTP 404)"
