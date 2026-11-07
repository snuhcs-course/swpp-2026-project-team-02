package com.swpp.licenseplanner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.swpp.licenseplanner.data.CalendarDestination
import com.swpp.licenseplanner.data.CalendarWriteOutcome
import com.swpp.licenseplanner.data.ExportNotStarted
import com.swpp.licenseplanner.domain.CalendarExport
import com.swpp.licenseplanner.ui.CalendarExportPhase
import com.swpp.licenseplanner.ui.CalendarExportState
import com.swpp.licenseplanner.ui.ExportBlock
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.Paper

/** The plan screens' export action (frame 2007:352), with the runtime study-session count. */
internal fun exportButtonText(count: Int) = "캘린더에 ${count}개 일정 추가"

/** Generic device-calendar wording: an arbitrary calendar cannot promise Google sync. */
internal const val EXPORT_SUBTITLE = "선택한 캘린더로 단방향 내보내기"

internal fun exportBlockText(block: ExportBlock) = when (block) {
    ExportBlock.NoStudy -> "내보낼 공부 일정이 없어요"
    ExportBlock.Recovery -> "계획 조정 화면에서 이 계획의 상태를 먼저 확인해 주세요"
    ExportBlock.Storage -> "계획을 기기에 저장한 뒤 내보낼 수 있어요"
    ExportBlock.Busy -> "진행 중인 작업이 끝나면 내보낼 수 있어요"
}

internal const val DUPLICATE_WARNING = "다시 내보내면 일정이 중복될 수 있어요."
internal const val NO_UPDATE_WARNING = "계획을 조정해도 캘린더 일정은 바뀌지 않아요."
internal const val DEMO_WRITE_WARNING = "데모 일정도 실제 캘린더에 추가돼요."
internal const val TIME_BASIS = "한국 시간 (${CalendarExport.TIME_ZONE})"

internal data class ExportMessage(val title: String, val body: String)

private val permissionMessage = ExportMessage(
    "캘린더 권한이 없어요",
    "일정을 추가하지 않았어요. 설정 › 앱 › 권한에서 캘린더를 허용한 뒤 다시 내보내 주세요.",
)

/** What one finished export did, with only confirmed counts; [total] is the number of events it was to add. */
internal fun exportResultMessage(outcome: CalendarWriteOutcome, destination: CalendarDestination, total: Int): ExportMessage = when (outcome) {
    is CalendarWriteOutcome.Complete -> ExportMessage(
        "${outcome.added}개 일정을 ${destination.label}에 추가했어요",
        "이 기기의 캘린더가 새 일정으로 받았어요. $NO_UPDATE_WARNING 다른 기기나 웹에 보이는 시점은 캘린더 계정의 동기화에 따라 달라요.",
    )
    is CalendarWriteOutcome.NotStarted -> when (outcome.reason) {
        ExportNotStarted.PermissionMissing -> permissionMessage
        ExportNotStarted.CalendarsUnavailable -> ExportMessage("캘린더를 확인하지 못했어요", "일정을 추가하지 않았어요. 잠시 후 다시 내보내 주세요.")
        ExportNotStarted.DestinationUnavailable -> ExportMessage(
            "선택한 캘린더에 추가할 수 없어요",
            "일정을 추가하지 않았어요. 캘린더가 삭제되었거나 쓰기 권한이 바뀌었을 수 있어요. 다시 내보내서 다른 캘린더를 골라 주세요.",
        )
        ExportNotStarted.PlanUnavailable -> ExportMessage(
            "이 계획은 지금 내보낼 수 없어요",
            "일정을 추가하지 않았어요. 계획이 바뀌었거나 다른 작업이 진행 중이에요.",
        )
    }
    is CalendarWriteOutcome.Stopped -> if (outcome.uncertain) {
        ExportMessage(
            "일정 추가 결과를 확인할 수 없어요",
            "${destination.label}에 ${outcome.added}개 일정이 추가된 것을 확인했어요. 그 밖의 일정도 추가되었을 수 있어요. " +
                "다시 내보내기 전에 캘린더에서 먼저 확인해 주세요.",
        )
    } else {
        ExportMessage(
            "일정 추가가 중간에 멈췄어요",
            "${destination.label}에 ${outcome.added}개 일정을 추가한 뒤 멈췄어요. 나머지 ${total - outcome.added}개는 추가하지 않았어요. " +
                "다시 내보내면 이미 추가된 일정이 중복되니, 먼저 캘린더를 확인해 주세요.",
        )
    }
}

/**
 * The shared export flow for both plan screens: permission, calendar choice, confirmation,
 * progress and result. While writing it cannot be closed, by back or otherwise; before that,
 * back or 취소 ends the flow without a write. There is no retry: another export starts over.
 */
@Composable
fun CalendarExportDialog(
    state: CalendarExportState,
    onChoose: (CalendarDestination) -> Unit,
    onReview: () -> Unit,
    onChooseAnother: () -> Unit,
    onConfirm: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
) {
    val phase = state.phase
    val writing = phase is CalendarExportPhase.Writing
    val count = state.source.events.size
    Dialog(onDismissRequest = onClose, properties = DialogProperties(dismissOnBackPress = !writing, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.large, color = Paper) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (phase) {
                    CalendarExportPhase.RequestingPermission, CalendarExportPhase.AwaitingPermission -> {
                        Heading("캘린더 권한 확인")
                        Note("일정을 추가할 캘린더를 고르려면 캘린더 읽기·쓰기 권한이 필요해요. 허용해도 마지막 확인 전에는 아무 일정도 추가하지 않아요.")
                        SecondaryButton("취소", onClose)
                    }
                    CalendarExportPhase.PermissionDenied -> {
                        AlertCard(permissionMessage.title, permissionMessage.body)
                        PrimaryButton("설정 열기", onOpenSettings)
                        SecondaryButton("닫기", onClose)
                    }
                    CalendarExportPhase.LoadingCalendars -> {
                        LoadingBlock("캘린더 목록을 불러오는 중이에요")
                        SecondaryButton("취소", onClose)
                    }
                    CalendarExportPhase.CalendarsUnavailable -> {
                        AlertCard("캘린더 목록을 읽지 못했어요", "일정을 추가하지 않았어요. 잠시 후 다시 내보내 주세요.")
                        SecondaryButton("닫기", onClose)
                    }
                    CalendarExportPhase.NoCalendars -> {
                        AlertCard(
                            "일정을 추가할 수 있는 캘린더가 없어요",
                            "기기 설정이나 캘린더 앱에서 계정 또는 캘린더를 추가한 뒤 다시 내보내 주세요. 이 앱은 캘린더를 만들지 않아요.",
                        )
                        SecondaryButton("닫기", onClose)
                    }
                    CalendarExportPhase.InvalidRows -> {
                        AlertCard(
                            "내보낼 수 없는 공부 일정이 있어요",
                            "시간이 올바르지 않거나 ID가 겹치는 공부 일정이 있어서 아무 일정도 추가하지 않았어요. 계획은 그대로예요.",
                        )
                        SecondaryButton("닫기", onClose)
                    }
                    is CalendarExportPhase.Choosing -> {
                        Heading("캘린더 선택", "계획 ${state.source.planOrdinal}의 공부 일정 ${count}개를 추가할 캘린더를 골라 주세요.")
                        for (calendar in phase.calendars) {
                            ChoiceCard(selected = calendar == phase.selected, onClick = { onChoose(calendar) }, choiceLabel = calendar.label) {
                                Text(calendar.label, style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.weight(1f))
                            }
                        }
                        PrimaryButton(
                            "다음",
                            onReview,
                            enabled = phase.selected != null,
                            subtitle = if (phase.selected == null) "캘린더를 하나 골라 주세요" else null,
                        )
                        SecondaryButton("취소", onClose)
                    }
                    is CalendarExportPhase.Confirming -> {
                        Heading("캘린더에 일정 추가")
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            InfoRow("계획", "계획 ${state.source.planOrdinal}")
                            InfoRow("캘린더", phase.destination.label)
                            InfoRow("일정", "공부 일정 ${count}개")
                            InfoRow("시간 기준", TIME_BASIS)
                        }
                        if (state.source.isDemo) {
                            MutedBox {
                                Text("로컬 시연 계산", style = MaterialTheme.typography.titleSmall, color = Ink)
                                Note(DEMO_WRITE_WARNING)
                            }
                        }
                        Note("새 일정으로 추가돼요. $DUPLICATE_WARNING")
                        Note(NO_UPDATE_WARNING)
                        Note("이 기기의 캘린더에 추가해요. 다른 기기와의 동기화는 캘린더 계정에 따라 달라요.")
                        PrimaryButton("${count}개 일정 추가", onConfirm)
                        SecondaryButton("캘린더 다시 선택", onChooseAnother)
                        SecondaryButton("취소", onClose)
                    }
                    is CalendarExportPhase.Writing -> LoadingBlock(
                        "캘린더에 일정을 추가하는 중이에요",
                        "끝날 때까지 기다려 주세요. 같은 일정이 다시 추가되지 않도록 이 화면은 닫을 수 없어요.",
                    )
                    is CalendarExportPhase.Finished -> {
                        val message = exportResultMessage(phase.outcome, phase.destination, count)
                        if (phase.outcome is CalendarWriteOutcome.Complete) {
                            MutedBox {
                                Text(message.title, style = MaterialTheme.typography.titleSmall, color = Ink)
                                Note(message.body)
                            }
                        } else {
                            AlertCard(message.title, message.body)
                        }
                        if (phase.outcome == CalendarWriteOutcome.NotStarted(ExportNotStarted.PermissionMissing)) {
                            PrimaryButton("설정 열기", onOpenSettings)
                            SecondaryButton("닫기", onClose)
                        } else {
                            PrimaryButton("확인", onClose)
                        }
                    }
                }
            }
        }
    }
}
