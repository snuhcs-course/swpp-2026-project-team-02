package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import com.swpp.licenseplanner.BuildConfig
import com.swpp.licenseplanner.ui.GenerationState
import com.swpp.licenseplanner.ui.PlanStorage
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.LoadingBlock
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.userMessage

/**
 * Loading while the single request is in flight (back is held so the result
 * is not lost), then an error with retry of the same request and a way back to edit.
 * A new plan is shown once it is saved on the device; the navigation host routes success and
 * the demo shortfall away. If saving fails, the plan can be saved again or viewed unsaved.
 */
@Composable
fun GenerateScreen(
    state: GenerationState,
    storage: PlanStorage,
    onRetry: () -> Unit,
    onEdit: () -> Unit,
    onRetrySave: () -> Unit,
    onShowPlan: () -> Unit,
) {
    val saving = state is GenerationState.Success && storage == PlanStorage.Saving
    BackHandler(enabled = state == GenerationState.Loading || saving) {}
    val failure = state as? GenerationState.Failure
    val unsaved = state is GenerationState.Success && storage == PlanStorage.SaveFailure
    AppScreen(
        title = "계획 만들기",
        navIcon = null,
        footer = when {
            failure != null -> {
                {
                    PrimaryButton("다시 시도", onRetry, enabled = storage == PlanStorage.Ready)
                    SecondaryButton("입력 수정하기", onEdit)
                }
            }
            unsaved -> {
                {
                    PrimaryButton("다시 저장하기", onRetrySave)
                    SecondaryButton("저장하지 않은 계획 보기", onShowPlan)
                }
            }
            else -> null
        },
    ) {
        when {
            failure != null -> {
                Heading("계획을 만들지 못했어요", failure.error.userMessage())
                Note("입력한 내용은 그대로 있어요. 다시 시도하면 같은 요청을 다시 보내요.")
            }
            unsaved -> {
                Heading("계획을 만들었지만 저장하지 못했어요")
                AlertCard(
                    "기기에 계획을 저장하지 못했어요",
                    "앱을 닫으면 이 계획이 사라질 수 있어요. 다시 저장해도 계획을 다시 만들지 않아요. " +
                        "저장하기 전에는 다른 계획을 만들거나 조정할 수 없어요.",
                )
            }
            saving -> LoadingBlock("계획을 기기에 저장하는 중이에요")
            else -> LoadingBlock(
                "계획을 만드는 중이에요",
                if (BuildConfig.DEMO_MODE) "로컬 시연 계산이에요." else "서버가 계획을 만드는 데 최대 2분까지 걸릴 수 있어요.",
            )
        }
    }
}

/**
 * Adapted from frame 2005:474: demo-only insufficient-time result with required and
 * available session counts and ways back to the exam-date editor (frame 2123:2) or
 * availability. No partial plan.
 */
@Composable
fun ShortfallScreen(required: Int, available: Int, onEditDates: () -> Unit, onEditAvailability: () -> Unit) {
    AppScreen(
        title = "공부 시간",
        onNav = onEditAvailability,
        footer = {
            PrimaryButton("시험 일정 바꾸기", onEditDates)
            SecondaryButton("공부 시간 늘리기", onEditAvailability)
        },
    ) {
        Heading("시험일 전에 시간이 부족해요")
        AlertCard(
            title = "필요한 공부 ${required}회 중 ${available}회만 들어가요",
            body = "시험일 전까지 고른 요일에 넣을 수 있는 공부는 ${available}회인데, 데모 계산으로는 ${required}회가 필요해요.",
        )
        Note("모의고사 답안과 공부 시간은 유지돼요.")
        Note("데모 플래너의 로컬 시연 계산이에요. 시험일을 늦추거나 공부하는 요일을 늘려 보세요.")
    }
}
