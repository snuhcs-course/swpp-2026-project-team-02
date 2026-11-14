package com.swpp.licenseplanner.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.ui.adjustmentMessage
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.reloadMessage
import com.swpp.licenseplanner.ui.userMessage

private data class He12ErrorCase(val label: String, val error: ApiError)

private val he12ErrorCases = listOf(
    He12ErrorCase("DNS / 네트워크 연결 실패", ApiError.NetworkUnavailable("java.net.UnknownHostException: api.example.invalid")),
    He12ErrorCase("요청 시간 초과", ApiError.RequestTimeout("java.net.SocketTimeoutException: Read timed out")),
    He12ErrorCase("서버 연결 거부", ApiError.ServerUnreachable("java.net.ConnectException: Failed to connect to api.example.invalid:443")),
    He12ErrorCase("서버 준비 안 됨 (503)", ApiError.ServerUnavailable("service is starting").apply { rawDiagnostic = "service is starting" }),
    He12ErrorCase("서버 처리 오류 (502)", ApiError.ServerFailure(502).apply { rawDiagnostic = "upstream timed out while reading response" }),
    He12ErrorCase("알 수 없는 HTTP 오류 (307)", ApiError.UnknownFailure(307, "HTTP 307 Temporary Redirect\nLocation: /api/v1/")),
    He12ErrorCase("알 수 없는 예외", ApiError.UnknownFailure(rawDiagnostic = "java.io.IOException: unexpected end of stream")),
    He12ErrorCase("입력 오류 (422)", ApiError.InvalidInput("exam_date is invalid")),
    He12ErrorCase("API 경로 오류 (404)", ApiError.Configuration),
)

private val he12Targets = listOf("문항 불러오기", "계획 생성", "계획 조정", "최신 계획 다시 불러오기")

/** Demo-only simulator for reviewing HE-12 error wording and the diagnostic disclosure. */
@Composable
fun He12ErrorDemoScreen(onBack: () -> Unit, onStartScenario: (Int, ApiError) -> Unit) {
    var selectedTarget by rememberSaveable { mutableIntStateOf(0) }
    var selectedError by rememberSaveable { mutableIntStateOf(0) }
    val errorCase = he12ErrorCases[selectedError]
    val message = when (selectedTarget) {
        2 -> errorCase.error.adjustmentMessage()
        3 -> errorCase.error.reloadMessage()
        else -> errorCase.error.userMessage()
    }

    AppScreen(
        title = "HE-12 오류 시연",
        onNav = onBack,
        footer = {
            PrimaryButton(
                text = when (selectedTarget) {
                    0 -> "오류 주입 후 문항 흐름 시작"
                    1 -> "오류 주입 후 계획 생성 흐름 시작"
                    2 -> "오류 주입 후 계획 조정 흐름 시작"
                    else -> "오류 주입 후 재조회 흐름 시작"
                },
                onClick = { onStartScenario(selectedTarget, errorCase.error) },
            )
        },
    ) {
        Heading("오류 안내를 확인해요", "상황과 오류 원인을 바꿔 사용자 안내와 개발자용 세부 메시지를 확인하세요.")
        Note("Demo 전용이에요. 선택한 요청 한 번만 가상 실패하고 재시도는 로컬 Demo 동작으로 진행돼요.")
        if (selectedTarget >= 2) Note("계획 조정과 재조회 시나리오는 먼저 Demo 계획을 하나 만들어야 해요.")

        Heading("화면 상황")
        he12Targets.forEachIndexed { index, label ->
            SecondaryButton(label, onClick = { selectedTarget = index }, selected = selectedTarget == index)
        }

        Heading("오류 원인")
        he12ErrorCases.forEachIndexed { index, case ->
            SecondaryButton(case.label, onClick = { selectedError = index }, selected = selectedError == index)
        }

        Heading("사용자에게 표시되는 오류 안내")
        AlertCard(
            when (selectedTarget) {
                0 -> "예시 문항을 불러오지 못했어요"
                1 -> "계획을 만들지 못했어요"
                2 -> "일정을 조정하지 못했어요"
                else -> "최신 계획을 불러오지 못했어요"
            },
            message,
        )
        key(selectedTarget, selectedError) { ErrorDetails(errorCase.error) }
        Note("아래 버튼을 누르면 오류를 주입한 뒤 실제 앱 화면으로 이동해요. 재조회 시나리오는 먼저 조정 실패를 보여준 다음, 최신 계획 재조회를 실패시켜요.")
    }
}
