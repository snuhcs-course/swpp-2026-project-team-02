package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.swpp.licenseplanner.BuildConfig
import com.swpp.licenseplanner.ui.GenerationState
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.LoadingBlock
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.userMessage

/**
 * GEN-03/04: loading while the single request is in flight (back is held so the result
 * is not lost), then an error with retry of the same request and a way back to edit.
 * Success and the demo shortfall are routed away by the navigation host.
 */
@Composable
fun GenerateScreen(state: GenerationState, onRetry: () -> Unit, onEdit: () -> Unit) {
    BackHandler(enabled = state == GenerationState.Loading) {}
    val failure = state as? GenerationState.Failure
    AppScreen(
        title = "계획 만들기",
        navIcon = null,
        footer = if (failure != null) {
            {
                PrimaryButton("다시 시도", onRetry)
                SecondaryButton("입력 수정하기", onEdit)
            }
        } else null,
    ) {
        if (failure != null) {
            Heading("계획을 만들지 못했어요", failure.error.userMessage())
            Note("입력한 내용은 그대로 있어요. 다시 시도하면 같은 요청을 다시 보내요.")
        } else {
            LoadingBlock(
                "계획을 만드는 중이에요",
                if (BuildConfig.DEMO_MODE) "로컬 시연 계산이에요." else "서버가 계획을 만드는 데 최대 2분까지 걸릴 수 있어요.",
            )
        }
    }
}

/**
 * GEN-06, adapted from frame 2005:474: demo-only insufficient-time result with required and
 * available session counts and ways back to the dates or availability. No partial plan.
 */
@Composable
fun ShortfallScreen(required: Int, available: Int, onEditDates: () -> Unit, onEditAvailability: () -> Unit) {
    AppScreen(
        title = "공부 시간",
        onNav = onEditAvailability,
        footer = {
            PrimaryButton("시험 날짜 바꾸기", onEditDates)
            SecondaryButton("공부 시간 늘리기", onEditAvailability)
        },
    ) {
        Heading("시험일 전에 시간이 부족해요")
        AlertCard(
            title = "필요한 공부 ${required}회 중 ${available}회만 들어가요",
            body = "시험일 전까지 고른 요일에 넣을 수 있는 공부는 ${available}회인데, 데모 계산으로는 ${required}회가 필요해요.",
        )
        Note("데모 플래너의 로컬 시연 계산이에요. 시험일을 늦추거나 공부하는 요일을 늘려 보세요.")
    }
}
