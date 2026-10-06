package com.swpp.licenseplanner.ui

import com.swpp.licenseplanner.data.ApiError

/** User-facing text for design D7's error table (GEN-04); the README lists the same cases. */
fun ApiError.userMessage(): String = when (this) {
    is ApiError.InvalidInput -> message?.takeIf { it.isNotBlank() } ?: "입력값을 확인해 주세요."
    ApiError.Configuration -> "연결 또는 설정 문제가 있어요. API 주소를 확인해 주세요. (HTTP 404)"
    is ApiError.ServerUnavailable -> "서버가 아직 준비되지 않았어요. 잠시 후 다시 시도해 주세요. (HTTP 503)"
    is ApiError.ServerFailure -> "서버에서 요청을 처리하지 못했어요. 다시 시도해 주세요. (HTTP $status)"
    ApiError.Connection -> "서버에 연결할 수 없어요. 네트워크와 서버 상태를 확인한 뒤 다시 시도해 주세요."
    is ApiError.InvalidResponse -> "서버 응답을 읽을 수 없어요. 다시 시도해 주세요."
}
