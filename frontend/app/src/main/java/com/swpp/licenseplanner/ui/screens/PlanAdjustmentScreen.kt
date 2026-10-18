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
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.ui.AdjustmentSelection
import com.swpp.licenseplanner.ui.AdjustmentState
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
import com.swpp.licenseplanner.ui.theme.Ink

/**
 * The selected plan's schedules by week with one selectable failed study
 * session, then progress, error with retry, or the updated rows. Day and session cards reuse
 * the plan screen's rendering (frame 2007:253). While a request runs, back and every other
 * action are held so the mutation is neither abandoned nor repeated.
 */
@Composable
fun PlanAdjustmentScreen(
    plan: SessionPlan,
    selection: AdjustmentSelection,
    onSelect: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onCreate: () -> Unit,
) {
    val state = selection.state
    val loading = state == AdjustmentState.Loading
    BackHandler(enabled = loading) {}

    val weeks = remember(plan.current.schedules) { PlanGrouping.weeks(plan.current.schedules) }
    var weekIndex by rememberSaveable(plan.ordinal) { mutableIntStateOf(0) }
    // Show the moved session's week after success.
    LaunchedEffect(state, weeks) {
        val target = (state as? AdjustmentState.Success)?.targetScheduleId ?: return@LaunchedEffect
        val index = weeks.indexOfFirst { week -> week.days.any { day -> day.sessions.any { it.scheduleId == target } } }
        if (index >= 0) weekIndex = index
    }
    val shownIndex = weekIndex.coerceIn(0, (weeks.size - 1).coerceAtLeast(0))
    val week = weeks.getOrNull(shownIndex)
    val selectable = plan.hasIdentity && plan.studyCount > 0
    val summary = plan.current.agentSummary?.takeIf { it.isNotBlank() }

    AppScreen(
        title = "실패한 일정 선택",
        navIcon = if (loading) null else NavIcon.Back,
        onNav = onBack,
        footer = {
            when {
                !plan.hasIdentity -> {
                    PrimaryButton("새 계획 만들기", onCreate)
                    SecondaryButton("목록으로", onBack)
                }
                plan.studyCount == 0 -> SecondaryButton("목록으로", onBack)
                state is AdjustmentState.Failure -> {
                    PrimaryButton("다시 시도", onRetry)
                    SecondaryButton("목록으로", onBack)
                }
                else -> PrimaryButton(
                    if (loading) "조정하는 중…" else "선택한 일정 조정하기",
                    onSubmit,
                    enabled = !loading && selection.failedScheduleId != null,
                    subtitle = if (!loading && selection.failedScheduleId == null) "놓친 공부 일정을 하나 골라 주세요" else null,
                )
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
        when {
            !plan.hasIdentity -> AlertCard(
                "이 계획은 조정할 수 없어요",
                "서버가 계획 파일 정보를 보내지 않았어요. 일정은 볼 수 있지만, 조정하려면 새 계획을 만들어 주세요.",
            )
            plan.studyCount == 0 -> OutlinedCard {
                Text("조정할 공부 일정이 없어요", style = MaterialTheme.typography.titleSmall, color = Ink)
                Note("이 계획에는 옮길 수 있는 공부 일정이 없어요. 목록으로 돌아가 다른 계획을 골라 주세요.")
            }
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
                Note(if (plan.isDemo) "선택한 일정은 그대로예요. 다시 시도할 수 있어요."
                    else "다시 시도하면 서버의 저장된 계획을 먼저 확인해요. 변경된 계획이 있으면 불러오고, 없으면 조정을 다시 요청해요.")
            }
            state == AdjustmentState.Refreshed -> MutedBox {
                Text("서버에 저장된 계획을 불러왔어요", style = MaterialTheme.typography.titleSmall, color = Ink)
                Note("이전 요청 또는 다른 작업으로 일정이 변경됐어요. 최신 일정을 확인하고 놓친 일정을 다시 골라 주세요.")
            }
            state == AdjustmentState.DemoNoSlot -> AlertCard(
                "옮길 수 있는 시간이 없어요",
                "시험일 전까지 이 계획의 공부 시간 중 비어 있는 곳이 없어요. 기존 계획은 그대로예요.",
            )
            else -> Note("놓친 공부 일정을 하나 고르세요. 기존 일정은 고를 수 없어요.")
        }
        if (week != null) {
            WeekNavigator(
                week = week,
                index = shownIndex,
                count = weeks.size,
                onPrevious = { weekIndex = shownIndex - 1 },
                onNext = { weekIndex = shownIndex + 1 },
            )
            // Selection stays visible while loading; the ViewModel ignores changes until the request ends.
            for (day in week.days) DaySection(day, selection.failedScheduleId, if (selectable) onSelect else null)
        }
    }
}
