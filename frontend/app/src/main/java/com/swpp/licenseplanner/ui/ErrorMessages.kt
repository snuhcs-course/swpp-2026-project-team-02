package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.data.ApiError

/** User-facing text for the error table in the README. Input advice points to the form's edit action. */
fun ApiError.userMessage(): String = userMessage(InputAdvice.EditForm)

private fun ApiError.userMessage(advice: InputAdvice): String = when (this) {
    is ApiError.InvalidInput -> invalidInputMessage(message, advice)
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
 * and a route 404 is not reported as a deleted plan. A selected plan keeps its own windows, so
 * input advice points to creating a new plan rather than editing the form.
 */
fun ApiError.adjustmentMessage(): String = when {
    this == ApiError.Configuration -> ROUTE_NOT_FOUND_MESSAGE
    this == ApiError.PlanConflict -> PLAN_CONFLICT_MESSAGE
    requiresReload() -> "조정 결과를 확인할 수 없어요. 서버에 반영되었을 수 있으니 최신 계획을 불러와 확인해 주세요."
    else -> userMessage(InputAdvice.NewPlan)
}

/** Latest-plan reload failure; the shown plan is still the earlier local copy. */
fun ApiError.reloadMessage(): String = "화면에는 이전 계획이 남아 있어요. " + when (this) {
    ApiError.Configuration -> ROUTE_NOT_FOUND_MESSAGE
    else -> userMessage(InputAdvice.NewPlan)
}

/** Where the user can fix rejected input from the current screen. */
private enum class InputAdvice { EditForm, NewPlan }

/**
 * A 422 is explained in Korean only for backend messages recognized exactly or by anchored
 * patterns; anything else gets a neutral fallback so raw diagnostics never reach the user.
 * Dynamic parts are limited to known weekdays and validated capacity numbers.
 */
private fun invalidInputMessage(message: String?, advice: InputAdvice): String {
    val text = message?.trim().orEmpty()
    if (text.startsWith(NOT_ENOUGH_TIME_CODE)) {
        return capacityMessage(text.removePrefix(NOT_ENOUGH_TIME_CODE).trim(), advice)
    }
    DATE_PROBLEM.matchEntire(text)?.let { match ->
        val field = if (match.groupValues[1] == "exam_date") "시험일" else "준비 시작일"
        return "${field}을 확인할 수 없어요.\n" + advice.fix("입력한 ${field}을 확인해", "${field}을 확인해")
    }
    if (text == "preparation_start must be before exam_date") {
        return "준비 시작일은 시험일보다 빨라야 해요.\n" + advice.fix("두 날짜를 확인해", "날짜를 확인해")
    }
    if (text in MISSING_WINDOWS) {
        return "공부할 수 있는 시간이 등록되지 않았어요.\n" +
            advice.fix("공부할 요일과 시간을 추가해", "공부할 요일과 시간을 넣어")
    }
    WINDOW_PROBLEM.matchEntire(text)?.let { match ->
        val day = WEEKDAY_NAMES.getValue(match.groupValues[1])
        val problem = when {
            match.groupValues[2].endsWith("times must use HH:MM") -> "$day 공부 시간의 시각 형식이 올바르지 않아요."
            match.groupValues[2].endsWith("start must be before end") -> "$day 공부 시간은 시작 시각이 끝 시각보다 빨라야 해요."
            else -> "$day 공부 시간이 서로 겹쳐요."
        }
        return "$problem\n" + advice.fix("$day 시간을 고쳐", "$day 시간을 올바르게 넣어")
    }
    if (ASSESSMENT_PROBLEMS.any { it.matches(text) }) {
        return "답안 정보를 서버에서 처리하지 못했어요. 선택한 답이 틀렸다는 뜻은 아니에요."
    }
    return FALLBACK_MESSAGE
}

/** Keeps the facts of a current capacity template; unfamiliar detail is dropped, never shown. */
private fun capacityMessage(detail: String, advice: InputAdvice): String {
    val facts = EXACT_CAPACITY.matchEntire(detail)?.let { match ->
        val minutes = match.groupValues[1].toIntOrNull()
        val topics = match.groupValues[2].toIntOrNull()
        if (minutes != null && topics != null && topics >= 1 && minutes < topics) {
            "준비 기간에 공부할 수 있는 시간이 ${minutes}분이라 진단된 학습 주제 ${topics}개에 주제마다 최소 1분씩 배정할 수 없어요.\n"
        } else null
    } ?: LOWER_BOUND_CAPACITY.matchEntire(detail)?.let { match ->
        if (match.groupValues[1].toIntOrNull() == 0) {
            "준비 기간에 공부할 수 있는 시간이 0분이라 학습 주제를 하나도 배정할 수 없어요.\n"
        } else null
    }.orEmpty()
    val fix = when (advice) {
        InputAdvice.EditForm -> "공부 가능 시간을 늘리거나, 준비를 더 일찍 시작하거나, 시험일을 늦춰 주세요. '입력 수정하기'에서 바꿀 수 있어요."
        InputAdvice.NewPlan -> "목록으로 돌아가 공부 시간이나 날짜를 바꿔 새 계획을 만들어 주세요."
    }
    return "시험일 전까지 공부할 시간이 부족해요.\n$facts$fix"
}

/** "…주세요." advice: edit the retained form, or start a new plan because this plan's inputs are fixed. */
private fun InputAdvice.fix(editForm: String, newPlan: String): String = when (this) {
    InputAdvice.EditForm -> "'입력 수정하기'에서 $editForm 주세요."
    InputAdvice.NewPlan -> "이 계획에 저장된 입력은 바꿀 수 없어요. 목록으로 돌아가 $newPlan 새 계획을 만들어 주세요."
}

private const val NOT_ENOUGH_TIME_CODE = "NOT_ENOUGH_TIME:"
private const val FALLBACK_MESSAGE = "요청 정보를 확인하지 못했어요."
private const val PLAN_NOT_FOUND_MESSAGE =
    "서버에서 이 계획을 찾을 수 없어요. 화면에는 이전 계획이 남아 있어요. 목록에서 다른 계획을 고르거나 새 계획을 만들어 주세요."
private const val PLAN_CONFLICT_MESSAGE = "서버의 계획이 변경되었어요. 최신 계획을 불러온 뒤 일정을 다시 선택해 주세요."
private const val ROUTE_NOT_FOUND_MESSAGE = "API 경로를 찾을 수 없어요. API 주소와 서버 버전을 확인해 주세요. (HTTP 404)"

private val WEEKDAY_NAMES = mapOf(
    "monday" to "월요일", "tuesday" to "화요일", "wednesday" to "수요일", "thursday" to "목요일",
    "friday" to "금요일", "saturday" to "토요일", "sunday" to "일요일",
)
private val WEEKDAY_PATTERN = WEEKDAY_NAMES.keys.joinToString("|")

private val DATE_PROBLEM = Regex(
    "(exam_date|preparation_start)(?: is invalid| is required| must use YYYY/MM/DD/HH/MM format|: .+)",
)
private val MISSING_WINDOWS = setOf(
    "weekly_availability is required. Enter at least one weekday and available time window.",
    "weekly_availability must contain at least one study window",
)
private val WINDOW_PROBLEM = Regex(
    "($WEEKDAY_PATTERN) (window \\d+ times must use HH:MM|window \\d+ start must be before end|availability windows cannot overlap)",
)
private val ASSESSMENT_PROBLEMS = listOf(
    Regex("assessment_results (?:is required|must be an object|certification_id does not match the request)"),
    Regex("assessment_results\\.results must (?:be an array|contain at least one item)"),
    Regex("each assessment_results item must be an object"),
    Regex("assessment result problem_id must be (?:an integer|positive)"),
    Regex("Duplicate assessment result for problem_id: \\S+"),
    Regex(
        "(?:question must be a non-empty string|choices must be a string map|correct_answer must be a non-empty string|" +
            "user_answer must be a non-empty string|user_answer and correct_answer must be choice keys|" +
            "is_correct must be a boolean|possible_score must be positive) for problem_id: \\S+",
    ),
)

private const val CAPACITY_ADVICE = " 공부 가능 시간을 늘리거나, 준비를 더 일찍 시작하거나, 시험일을 늦춰 주세요\\."
private val EXACT_CAPACITY = Regex(
    "준비 시작부터 시험일까지 공부할 수 있는 시간이 (\\d{1,9})분이라 진단된 학습 주제 (\\d{1,9})개에 주제마다 최소 1분씩 배정할 수 없습니다\\.$CAPACITY_ADVICE",
)
private val LOWER_BOUND_CAPACITY = Regex(
    "준비 시작부터 시험일까지 공부할 수 있는 시간이 (\\d{1,9})분이라 학습 주제 1개에도 최소 1분을 배정할 수 없습니다\\.$CAPACITY_ADVICE",
)
