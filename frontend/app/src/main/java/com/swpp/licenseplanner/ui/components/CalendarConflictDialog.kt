package com.swpp.licenseplanner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.swpp.licenseplanner.data.CalendarReadOutcome
import com.swpp.licenseplanner.domain.StudyInterval
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.CalendarConflictState
import com.swpp.licenseplanner.ui.ConflictKind
import com.swpp.licenseplanner.ui.ConflictPhase
import com.swpp.licenseplanner.ui.GenerationState
import com.swpp.licenseplanner.ui.userMessage
import com.swpp.licenseplanner.ui.theme.Paper
import java.time.format.DateTimeFormatter

internal const val CALENDAR_SNAPSHOT_NOTICE = "확인한 시점의 일정이에요. 다른 앱에서 이후에 바꾸면 다시 겹칠 수 있어요."
internal const val REPLACEMENT_NOTICE = "선택한 계획을 새 계획으로 바꿔요. 이 기기에 저장되면 이 계획의 이전 공부 기록이 지워져요. 이미 내보낸 캘린더 일정은 그대로예요."
internal const val UNCHECKED_NOTICE = "캘린더를 확인하지 못했어요. 겹치는 일정이 있는지 알 수 없어요."

private val conflictDate = DateTimeFormatter.ofPattern("M월 d일(E)", java.util.Locale.KOREAN)
private fun intervalText(interval: StudyInterval): String =
    "${interval.start.format(conflictDate)} ${WireDateTime.formatTime(interval.start.toLocalTime())}–" +
        (if (interval.end.toLocalDate() == interval.start.toLocalDate()) "" else "${interval.end.format(conflictDate)} ") +
        WireDateTime.formatTime(interval.end.toLocalTime())

/** The explicit action owns this scrollable timing-only review until it dispatches or is dismissed. */
@Composable
fun CalendarConflictDialog(
    state: CalendarConflictState,
    generation: GenerationState,
    onContinue: (Boolean) -> Unit,
    onDetails: () -> Unit,
    onSummary: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    regenerationReason: String?,
    onConfirmReplacement: () -> Unit,
    onRetryRead: () -> Unit,
    onRetryGeneration: () -> Unit,
    onManual: (String) -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val export = state.kind == ConflictKind.Export
    Dialog(onDismissRequest = onClose, properties = DialogProperties(dismissOnBackPress = !state.running, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.large, color = Paper) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                state.source?.let {
                    InfoRow("계획", "계획 ${it.ordinal}")
                    InfoRow("준비 기간", "${it.original.preparationStart.take(10)}–${it.original.examDate.take(10)}")
                }
                state.exportConfirmation?.let { InfoRow("캘린더", it.destination.label) }
                when (state.phase) {
                    ConflictPhase.ReplacementConsent -> {
                        Heading("이 계획을 다시 만들까요?")
                        Note(REPLACEMENT_NOTICE)
                        Note("원래 답안·준비 기간·공부 시간에 새로 확인한 캘린더 시간을 반영해요.")
                        PrimaryButton("바꾸고 다시 만들기", onConfirmReplacement)
                        SecondaryButton("취소", onClose)
                    }
                    ConflictPhase.RequestingPermission, ConflictPhase.AwaitingPermission, ConflictPhase.Reading -> {
                        LoadingBlock("캘린더 시간을 확인하는 중이에요")
                        Note("일정 제목을 읽지 않고 시간만 확인해요.")
                        SecondaryButton("취소", onClose)
                    }
                    ConflictPhase.Review -> {
                        val count = state.conflicts.matches.map { it.target }.distinct().size
                        Heading(if (count == 0) "새로 확인한 캘린더 시간" else "공부 시간과 캘린더가 겹쳐요")
                        InfoRow(if (export) "겹치는 공부 일정" else "겹치는 공부 시간대", "${count}개")
                        InfoRow("확인한 외부 일정", "${state.snapshot?.schedules?.size ?: 0}개")
                        Note(CALENDAR_SNAPSHOT_NOTICE)
                        for (day in state.conflicts.recurringWeekdays) Note("${day.label}요일에 반복해서 겹쳐요. 그 요일의 공부 시간을 바꿔 보세요.")
                        if (export) {
                            PrimaryButton("겹치지 않게 다시 만들기", onRegenerate, enabled = regenerationReason == null, subtitle = regenerationReason)
                            SecondaryButton("하나씩 확인", onDetails)
                            SecondaryButton(if (count == 0) "내보내기 확인으로" else "겹쳐도 내보내기", { onContinue(false) })
                        } else {
                            PrimaryButton("겹치지 않게 계획 만들기", { onContinue(false) })
                            SecondaryButton("하나씩 확인", onDetails)
                            SecondaryButton("공부 시간 수정", onEdit)
                        }
                        SecondaryButton("취소", onClose)
                    }
                    ConflictPhase.Details -> {
                        Heading("겹치는 시간 하나씩 확인")
                        for (match in state.conflicts.matches) {
                            MutedBox {
                                val id = match.target.scheduleId
                                val row = state.source?.current?.schedules?.firstOrNull { it.scheduleId == id }
                                Note(row?.topic ?: "설정한 공부 시간")
                                Note(intervalText(match.target.interval))
                                Note("외부 일정 · ${intervalText(StudyInterval(match.occurrence.start, match.occurrence.end))}")
                                if (id != null && state.source?.canAdjust == true) SecondaryButton("이 공부 시간 직접 옮기기", { onManual(id) })
                            }
                        }
                        if (state.conflicts.matches.isEmpty()) Note("현재 겹치는 시간이 없어요.")
                        SecondaryButton("요약으로", onSummary)
                        if (state.kind == ConflictKind.Creation) SecondaryButton("공부 시간 수정", onEdit)
                        SecondaryButton("취소", onClose)
                    }
                    ConflictPhase.Unchecked -> {
                        AlertCard("캘린더 확인 안 됨", UNCHECKED_NOTICE)
                        if (state.kind != ConflictKind.Export && state.request.busySchedules.isNotEmpty()) {
                            Note("이전에 가져온 외부 일정 ${state.request.busySchedules.size}개를 유지해요. 최신 정보가 아닐 수 있어요.")
                        }
                        PrimaryButton("다시 확인", onRetryRead)
                        if (!export || state.outcome != CalendarReadOutcome.PermissionMissing) SecondaryButton("확인 없이 계속", { onContinue(true) })
                        if (state.outcome == CalendarReadOutcome.PermissionMissing) SecondaryButton("설정 열기", onSettings)
                        SecondaryButton("취소", onClose)
                    }
                    ConflictPhase.Generating -> LoadingBlock("새 계획을 만드는 중이에요")
                    ConflictPhase.Saving -> LoadingBlock("새 계획을 이 기기에 저장하는 중이에요")
                    ConflictPhase.Unsaved -> {
                        AlertCard("새 계획을 저장하지 못했어요", "이전 계획과 공부 기록을 유지하고 있어요. 닫은 뒤 화면의 다시 저장하기로 같은 새 계획을 저장해 주세요.")
                        SecondaryButton("닫기", onClose)
                    }
                    ConflictPhase.Replaced -> {
                        Heading("새 계획을 이 기기에 저장했어요")
                        Note("선택한 계획의 이전 공부 기록을 지웠어요. 다른 계획과 임시 저장 입력은 유지했어요.")
                        PrimaryButton("확인", onClose)
                    }
                    ConflictPhase.Failed -> {
                        val failure = generation as? GenerationState.Failure
                        val shortfall = generation as? GenerationState.DemoShortfall
                        AlertCard("계획을 다시 만들지 못했어요", failure?.error?.userMessage()
                            ?: "필요한 공부 ${shortfall?.required ?: 0}개를 넣을 시간 ${shortfall?.available ?: 0}개가 있어요. 공부 시간을 늘린 새 계획을 만들어 주세요.")
                        Note("이전 계획과 공부 기록을 유지하고 있어요.")
                        if (failure != null) {
                            Note("응답을 받지 못했다면 서버에 새 계획이 남아 있을 수 있어요. 다시 시도하면 새 계획이 하나 더 생길 수 있어요.")
                            PrimaryButton("다시 시도", onRetryGeneration)
                        }
                        SecondaryButton("닫기", onClose)
                    }
                }
            }
        }
    }
}
