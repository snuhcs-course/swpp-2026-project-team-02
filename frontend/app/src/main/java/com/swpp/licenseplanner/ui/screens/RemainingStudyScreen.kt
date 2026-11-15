package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.StudyCheckInStatus
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.MoveSave
import com.swpp.licenseplanner.ui.PlanStorage
import com.swpp.licenseplanner.ui.RemainingStudyState
import com.swpp.licenseplanner.ui.components.*
import com.swpp.licenseplanner.ui.remainingFailureMessage
import com.swpp.licenseplanner.ui.theme.Ink

internal fun remainingSaveText(save: MoveSave): String = when (save) {
    MoveSave.Saving -> "이 기기에 저장하는 중이에요"
    MoveSave.Saved -> "이 기기에 저장했어요"
    MoveSave.Unsaved -> "아직 이 기기에 저장하지 못했어요."
}

/** Review and all outcomes use the same scrollable, text-scaling shell as the plan screens. */
@Composable
fun RemainingStudyScreen(
    state: RemainingStudyState,
    storage: PlanStorage,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
    onRetrySave: () -> Unit,
    onShowPlan: () -> Unit,
) {
    val input = when (state) {
        is RemainingStudyState.Review -> state.input
        is RemainingStudyState.Loading -> state.input
        is RemainingStudyState.Success -> state.input
        is RemainingStudyState.Failure -> state.input
        is RemainingStudyState.Invalid -> null
    }
    val busy = state is RemainingStudyState.Loading || storage == PlanStorage.Saving
    BackHandler(enabled = busy) {}
    BackHandler(enabled = !busy, onBack = onClose)
    AppScreen("남은 공부 다시 계획하기", navIcon = if (busy) null else NavIcon.Back, onNav = onClose) {
        input?.let { Heading("계획 ${it.source.ordinal} · $CERTIFICATION_NAME") }
        if (input?.source?.isDemo == true) Note("로컬 시연 계산 · 서버나 AI를 사용하지 않아요.")
        when (state) {
            is RemainingStudyState.Review -> {
                if (state.input.work.totalMinutes == 0L) {
                    MutedBox {
                        Text("다시 배치할 남은 공부가 없어요", style = MaterialTheme.typography.titleSmall, color = Ink)
                        Note("남은 시간 0분")
                    }
                    PrimaryButton("계획으로 돌아가기", onClose)
                } else {
                    Heading("남은 시간 ${state.input.work.totalMinutes}분")
                    for (contribution in state.input.work.contributions) {
                        MutedBox {
                            val status = when (contribution.report?.status) {
                                StudyCheckInStatus.Done -> "다 했어요"
                                StudyCheckInStatus.Partly -> "일부 했어요"
                                StudyCheckInStatus.Missed -> "못 했어요"
                                null -> "기록 없음"
                            }
                            Text("${contribution.source.topic} · $status", style = MaterialTheme.typography.titleSmall, color = Ink)
                            Note(remainingDatedTime(contribution.source.startDate, contribution.source.endDate))
                            Note("예정 ${contribution.plannedMinutes}분 · 실제 ${contribution.report?.actualMinutes?.let { "${it}분" } ?: "기록 없음"} · 남은 시간 ${contribution.remainingMinutes}분")
                        }
                    }
                    Note("저장된 캘린더 시간을 사용해요. 현재 캘린더를 다시 읽거나 일정을 추가하지 않아요.")
                    if (state.input.work.contributions.any { it.report != null && it.remainingMinutes > 0 }) {
                        Note("다시 배치하는 일부 했어요·못 했어요 일정의 이전 공부 기록은 그대로 저장되지만 현재 계획에서는 표시되지 않아요. 새 일정은 기록 없음으로 시작해요.")
                    }
                    Note("진행 중인 일정도 기록이 없으면 예정 시간 전체를 다시 배치해요. 일부 공부했다면 취소하고 먼저 기록해 주세요.")
                    PrimaryButton("남은 공부 다시 배치하기", onConfirm, enabled = storage == PlanStorage.Ready)
                    SecondaryButton("취소", onClose)
                }
            }
            is RemainingStudyState.Loading -> LoadingBlock("남은 공부를 배치하는 중이에요", "완료될 때까지 기다려 주세요.")
            is RemainingStudyState.Success -> {
                MutedBox {
                    Text("남은 공부 ${state.input.work.totalMinutes}분을 배치했어요", style = MaterialTheme.typography.titleSmall, color = Ink)
                    Note("${state.input.work.instructions.size}개 일정 · ${remainingSaveText(state.save)}")
                }
                val ids = state.input.work.instructions.map { it.replacementScheduleId }.toSet()
                for (row in state.rows.filter { it.scheduleId in ids }) {
                    MutedBox {
                        Text(row.topic, style = MaterialTheme.typography.titleSmall, color = Ink)
                        Note(remainingDatedTime(row.startDate, row.endDate))
                        Note("${state.input.work.instructions.single { it.replacementScheduleId == row.scheduleId }.remainingMinutes}분")
                    }
                }
                Note("휴대폰 캘린더의 기존 일정은 그대로예요. 새 시간을 복사하려면 계획에서 캘린더에 추가를 따로 진행해 주세요.")
                if (state.save == MoveSave.Unsaved) {
                    Note("다시 저장하면 이미 배치한 결과만 이 기기에 저장해요.")
                    PrimaryButton("다시 저장하기", onRetrySave)
                }
                PrimaryButton("계획 보기", onShowPlan, enabled = state.save != MoveSave.Saving)
            }
            is RemainingStudyState.Failure -> {
                AlertCard(if (state.noFit) "현재 배치 규칙으로 남은 공부를 모두 배치하지 못했어요" else "남은 공부를 다시 계획하지 못했어요",
                    if (state.noFit) "기존 계획과 공부 기록을 유지했어요. 다른 순서나 나눈 일정은 별도 검토가 필요해요." else remainingFailureMessage(state.error))
                PrimaryButton("계획으로 돌아가기", onShowPlan, enabled = !busy)
            }
            is RemainingStudyState.Invalid -> {
                AlertCard("다시 검토해 주세요", state.message)
                if (storage == PlanStorage.SaveFailure) PrimaryButton("다시 저장하기", onRetrySave)
                SecondaryButton("계획으로 돌아가기", onClose, enabled = !busy)
            }
        }
    }
}

internal fun remainingDatedTime(start: String, end: String): String {
    fun display(wire: String): String = WireDateTime.parseOrNull(wire)?.let {
        "${it.year}년 ${it.monthValue}월 ${it.dayOfMonth}일 ${WireDateTime.formatTime(it.toLocalTime())}"
    } ?: "시간 확인 필요"
    return "${display(start)} – ${display(end)}"
}
