package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.WeekPlan
import com.swpp.licenseplanner.ui.AdjustmentSelection
import com.swpp.licenseplanner.ui.AdjustmentState
import com.swpp.licenseplanner.ui.PlanStorage
import com.swpp.licenseplanner.ui.adjustmentMessage
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.LoadingBlock
import com.swpp.licenseplanner.ui.components.MutedBox
import com.swpp.licenseplanner.ui.components.NavIcon
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.OutlinedCard
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.reloadMessage
import com.swpp.licenseplanner.ui.theme.Ink

/**
 * What the adjustment screen offers. Recovery comes from the stored plan, so reopening a plan
 * shows its reload or missing state even after the transient outcome was cleared.
 */
internal enum class AdjustmentMode { Missing, ReloadNeeded, Reloading, ReloadFailed, NoIdentity, NoStudy, Adjustable }

internal fun adjustmentMode(plan: SessionPlan, state: AdjustmentState): AdjustmentMode = when {
    plan.recovery == PlanRecovery.Missing -> AdjustmentMode.Missing
    plan.recovery == PlanRecovery.ReloadRequired -> when (state) {
        AdjustmentState.Reloading -> AdjustmentMode.Reloading
        is AdjustmentState.ReloadFailure -> AdjustmentMode.ReloadFailed
        else -> AdjustmentMode.ReloadNeeded
    }
    !plan.hasIdentity -> AdjustmentMode.NoIdentity
    plan.studyCount == 0 -> AdjustmentMode.NoStudy
    else -> AdjustmentMode.Adjustable
}

/**
 * The study row moved by the current successful adjustment, or null. Derived from the transient
 * outcome only, so a new selection, reload or plan switch clears it.
 */
internal fun movedScheduleId(plan: SessionPlan, state: AdjustmentState): String? =
    (state as? AdjustmentState.Success)?.targetScheduleId?.takeIf { plan.isStudy(it) }

/** Index of the week holding [scheduleId], or -1. */
private fun weekIndexOf(weeks: List<WeekPlan>, scheduleId: String?): Int =
    weeks.indexOfFirst { week -> week.days.any { day -> day.sessions.any { it.scheduleId == scheduleId } } }

/**
 * The selected plan's schedules by week with one selectable failed study
 * session, then progress, error with retry, or the updated rows. After an uncertain result the
 * shown rows stay as an unconfirmed copy and only reading the latest saved plan is offered.
 * Day and session cards reuse the plan screen's rendering (frame 2007:253). While a request
 * runs, back and every other action are held so it is neither abandoned nor repeated.
 * Requests wait while the plans are being saved or after a failed save; viewing does not.
 */
@Composable
fun PlanAdjustmentScreen(
    plan: SessionPlan,
    selection: AdjustmentSelection,
    storage: PlanStorage,
    onSelect: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onRetrySave: () -> Unit,
) {
    val state = selection.state
    val loading = state == AdjustmentState.Loading
    val busy = loading || state == AdjustmentState.Reloading
    val canSend = storage == PlanStorage.Ready
    BackHandler(enabled = busy) {}
    val mode = adjustmentMode(plan, state)

    val weeks = remember(plan.current.schedules) { PlanGrouping.weeks(plan.current.schedules) }
    // Entering a plan that awaits reload opens its failed session's week; later browsing is free.
    var weekIndex by rememberSaveable(plan.ordinal) {
        val target = selection.failedScheduleId.takeIf { plan.recovery == PlanRecovery.ReloadRequired }
        mutableIntStateOf(weekIndexOf(weeks, target).coerceAtLeast(0))
    }
    // Show the moved session's week after success, and the first week of reloaded rows.
    LaunchedEffect(state, weeks) {
        when (state) {
            is AdjustmentState.Success -> {
                val index = weekIndexOf(weeks, state.targetScheduleId)
                if (index >= 0) weekIndex = index
            }
            AdjustmentState.Reloaded -> weekIndex = 0
            else -> Unit
        }
    }
    val shownIndex = weekIndex.coerceIn(0, (weeks.size - 1).coerceAtLeast(0))
    val week = weeks.getOrNull(shownIndex)
    val summary = plan.current.agentSummary?.takeIf { it.isNotBlank() }

    AppScreen(
        title = "실패한 일정 선택",
        navIcon = if (busy) null else NavIcon.Back,
        onNav = onBack,
        footer = {
            when (mode) {
                AdjustmentMode.Missing -> {
                    PrimaryButton("목록으로", onBack)
                    SecondaryButton("새 계획 만들기", onCreate)
                }
                AdjustmentMode.ReloadNeeded -> {
                    PrimaryButton("최신 계획 불러오기", onReload, enabled = canSend)
                    SecondaryButton("목록으로", onBack)
                }
                AdjustmentMode.Reloading -> PrimaryButton("불러오는 중…", onReload, enabled = false)
                AdjustmentMode.ReloadFailed -> {
                    PrimaryButton("최신 계획 다시 불러오기", onReload, enabled = canSend)
                    SecondaryButton("목록으로", onBack)
                }
                AdjustmentMode.NoIdentity -> {
                    PrimaryButton("새 계획 만들기", onCreate)
                    SecondaryButton("목록으로", onBack)
                }
                AdjustmentMode.NoStudy -> SecondaryButton("목록으로", onBack)
                AdjustmentMode.Adjustable -> if (state is AdjustmentState.Failure) {
                    PrimaryButton("다시 시도", onRetry, enabled = canSend)
                    SecondaryButton("목록으로", onBack)
                } else if (state == AdjustmentState.DemoNoSlot) {
                    // The same selection would give the same result; another session or a new plan can.
                    PrimaryButton("선택한 일정 조정하기", onSubmit, enabled = false, subtitle = "다른 일정을 선택해 주세요")
                    SecondaryButton("목록으로", onBack)
                    SecondaryButton("새 계획 만들기", onCreate)
                } else {
                    PrimaryButton(
                        if (loading) "조정하는 중…" else "선택한 일정 조정하기",
                        onSubmit,
                        enabled = !loading && selection.failedScheduleId != null && canSend,
                        subtitle = if (!loading && selection.failedScheduleId == null) "놓친 공부 일정을 하나 골라 주세요" else null,
                    )
                }
            }
        },
    ) {
        Heading(
            "계획 ${plan.ordinal} · $CERTIFICATION_NAME",
            "준비 ${wireDate(plan.original.preparationStart)} – 시험 ${wireDate(plan.original.examDate)}",
        )
        if (plan.isDemo) {
            MutedBox {
                Text("로컬 시연 계산", style = MaterialTheme.typography.titleSmall, color = Ink)
                Note("일정을 기기 안에서 옮겨요. 서버에 저장되지 않고 AI 결과도 아니에요.")
            }
        }
        StorageAlert(storage, onRetryLoad = {}, onRetrySave = onRetrySave)
        if (state == AdjustmentState.Reloaded && mode != AdjustmentMode.Missing) {
            MutedBox {
                Text("최신 저장 계획을 불러왔어요", style = MaterialTheme.typography.titleSmall, color = Ink)
                Note("조정할 일정을 다시 선택해 주세요.")
            }
        }
        when (mode) {
            AdjustmentMode.Missing -> AlertCard("이 계획은 더 이상 조정할 수 없어요", ApiError.PlanNotFound.adjustmentMessage())
            AdjustmentMode.ReloadNeeded -> AlertCard(
                "최신 계획을 확인해 주세요",
                (state as? AdjustmentState.Failure)?.error?.adjustmentMessage()
                    ?: "최신 계획을 불러와야 이 계획을 다시 조정할 수 있어요. 화면에는 이전 계획이 남아 있어요.",
            )
            AdjustmentMode.Reloading -> LoadingBlock(
                "최신 계획을 불러오는 중이에요",
                "저장된 계획을 읽기만 해요. 조정 요청은 다시 보내지 않아요.",
            )
            AdjustmentMode.ReloadFailed -> AlertCard(
                "최신 계획을 불러오지 못했어요",
                (state as AdjustmentState.ReloadFailure).error.reloadMessage(),
            )
            AdjustmentMode.NoIdentity -> AlertCard(
                "이 계획은 조정할 수 없어요",
                "서버가 계획 파일 정보를 보내지 않았어요. 일정은 볼 수 있지만, 조정하려면 새 계획을 만들어 주세요.",
            )
            AdjustmentMode.NoStudy -> OutlinedCard {
                Text("조정할 공부 일정이 없어요", style = MaterialTheme.typography.titleSmall, color = Ink)
                Note("이 계획에는 옮길 수 있는 공부 일정이 없어요. 목록으로 돌아가 다른 계획을 골라 주세요.")
            }
            AdjustmentMode.Adjustable -> when {
                loading -> LoadingBlock(
                    "일정을 조정하는 중이에요",
                    if (plan.isDemo) "로컬 시연 계산이에요." else "서버가 일정을 조정하는 데 최대 2분까지 걸릴 수 있어요.",
                )
                state is AdjustmentState.Success -> {
                    MutedBox {
                        Text("계획이 조정됐어요", style = MaterialTheme.typography.titleSmall, color = Ink)
                        Note("옮긴 일정이 있는 주를 보여 드려요. 다른 일정도 이어서 조정할 수 있어요.")
                    }
                    if (summary != null) AiSummary(summary)
                }
                state is AdjustmentState.Failure -> {
                    AlertCard("일정을 조정하지 못했어요", state.error.adjustmentMessage())
                    Note("선택한 일정은 그대로예요. 다시 시도하면 같은 요청을 다시 보내요.")
                }
                state == AdjustmentState.DemoNoSlot -> AlertCard(
                    "옮길 수 있는 시간이 없어요",
                    "시험일 전까지 이 계획의 공부 시간 중 비어 있는 곳이 없어요. 기존 계획은 그대로예요. " +
                        "다른 일정을 고르거나, 공부 시간을 넉넉히 넣어 새 계획을 만들어 주세요. 이 계획의 공부 시간은 바뀌지 않아요.",
                )
                state == AdjustmentState.Reloaded -> Unit
                else -> Note("놓친 공부 일정을 하나 고르세요. 기존 일정은 고를 수 없어요.")
            }
        }
        if (week != null) {
            WeekNavigator(
                week = week,
                index = shownIndex,
                count = weeks.size,
                onPrevious = { weekIndex = shownIndex - 1 },
                onNext = { weekIndex = shownIndex + 1 },
            )
            // Selection stays visible but disabled while loading or awaiting a reload; the ViewModel ignores changes then.
            val selectable = mode == AdjustmentMode.Adjustable && !loading
            val showChoices = selectable || selection.failedScheduleId != null
            val movedId = movedScheduleId(plan, state)
            for (day in week.days) {
                DaySection(day, selection.failedScheduleId, if (showChoices) onSelect else null, selectionEnabled = selectable, movedId = movedId)
            }
        }
    }
}
