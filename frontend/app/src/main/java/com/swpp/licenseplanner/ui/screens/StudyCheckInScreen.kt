package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.PlanSession
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.StudyCheckIn
import com.swpp.licenseplanner.domain.StudyCheckInStatus
import com.swpp.licenseplanner.ui.CheckInEditor
import com.swpp.licenseplanner.ui.CheckInInputError
import com.swpp.licenseplanner.ui.CheckInSave
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.ChoiceCard
import com.swpp.licenseplanner.ui.components.FieldLabel
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.InfoRow
import com.swpp.licenseplanner.ui.components.LoadingBlock
import com.swpp.licenseplanner.ui.components.NavIcon
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.OutlinedCard
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.components.Tag
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkBody
import com.swpp.licenseplanner.ui.theme.InkSecondary
import com.swpp.licenseplanner.ui.theme.Divider
import com.swpp.licenseplanner.ui.theme.Paper
import java.time.format.TextStyle
import java.util.Locale

internal const val UNREPORTED = "아직 기록하지 않았어요"
internal const val UNSAVED_REPORT = "저장 안 됨"
internal const val ACTUAL_MINUTES_FIELD = "actual-minutes"

internal fun checkInStatusLabel(status: StudyCheckInStatus) = when (status) {
    StudyCheckInStatus.Done -> "다 했어요"
    StudyCheckInStatus.Partly -> "일부 했어요"
    StudyCheckInStatus.Missed -> "못 했어요"
}

/** “다 했어요 · 실제 20분”: the reported status and the actual minutes, apart from the scheduled duration. */
internal fun checkInSummary(report: StudyCheckIn?): String =
    report?.let { "${checkInStatusLabel(it.status)} · 실제 ${it.actualMinutes}분" } ?: UNREPORTED

/** Reporting entries for the study rows of one shown plan. */
class CheckInRows(
    val reportFor: (String) -> StudyCheckIn?,
    val isUnsaved: (String) -> Boolean,
    val enabled: Boolean,
    val onOpen: (String) -> Unit,
)

/**
 * Below a study row: its report (or that none exists), an unsaved mark, and a separate action that
 * opens the report editor. The action never selects the row for adjustment.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CheckInRowAction(session: PlanSession, rowLabel: String, rows: CheckInRows) {
    val report = rows.reportFor(session.scheduleId)
    val unsaved = rows.isUnsaved(session.scheduleId)
    val action = if (report == null) "공부 기록하기" else "기록 수정하기"
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (report == null) MutedTag(UNREPORTED)
            else Text(checkInSummary(report), style = MaterialTheme.typography.labelMedium, color = InkBody)
            if (unsaved) Tag(UNSAVED_REPORT)
        }
        Surface(
            onClick = { rows.onOpen(session.scheduleId) },
            enabled = rows.enabled,
            shape = MaterialTheme.shapes.small,
            color = Paper,
            contentColor = if (rows.enabled) Ink else InkSecondary,
            border = BorderStroke(1.dp, if (rows.enabled) Ink else Divider),
            modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "$rowLabel $action" },
        ) {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                Text(action, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Gray tag for a session without a report. */
@Composable
private fun MutedTag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = InkBody,
        modifier = Modifier
            .background(Divider, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

private fun inputErrorText(error: CheckInInputError) = when (error) {
    CheckInInputError.StatusRequired -> "다 했어요, 일부 했어요, 못 했어요 중 하나를 골라 주세요."
    CheckInInputError.InvalidMinutes -> "실제로 공부한 시간을 1분 이상의 정수(분)로 적어 주세요."
}

/**
 * Report editor for one study session of one plan, adapted from the study-record frames: the
 * session card with its scheduled time and duration, a status choice, actual minutes apart from
 * the scheduled duration, and an explicit save. Cancel writes nothing. A failed save keeps the
 * edit visible as unsaved and offers the local retry. Skip, mastery and calendar-connection
 * actions from the frames are not offered.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StudyCheckInScreen(
    plan: SessionPlan,
    editor: CheckInEditor,
    onStatus: (StudyCheckInStatus) -> Unit,
    onMinutes: (String) -> Unit,
    onSave: () -> Unit,
    onRetrySave: () -> Unit,
    onClose: () -> Unit,
) {
    val session = PlanGrouping.sessions(plan.current.schedules).firstOrNull { it.scheduleId == editor.scheduleId }
    val saving = editor.save == CheckInSave.Saving
    val editing = editor.save == CheckInSave.Editing
    val keyboardVisible = WindowInsets.isImeVisible
    // Back cancels like the top-bar arrow; while saving it is held so the write is not abandoned.
    BackHandler { if (!saving) onClose() }

    val actions: @Composable ColumnScope.() -> Unit = {
        when (editor.save) {
            CheckInSave.Editing -> {
                PrimaryButton(if (editor.committed == null) "기록 저장하기" else "기록 수정하기", onSave)
                SecondaryButton("취소", onClose)
            }
            CheckInSave.Saving -> PrimaryButton("저장하는 중…", {}, enabled = false)
            CheckInSave.Unsaved -> {
                PrimaryButton("다시 저장하기", onRetrySave)
                SecondaryButton("계획으로 돌아가기", onClose)
            }
            CheckInSave.Saved -> PrimaryButton("계획으로 돌아가기", onClose)
        }
    }
    AppScreen(
        title = "공부 기록",
        navIcon = if (saving) null else NavIcon.Back,
        onNav = onClose,
        footer = if (keyboardVisible) null else actions,
    ) {
        val date = session?.start?.toLocalDate()
        Heading(
            date?.let { "${monthDay(it)} ${it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}" } ?: "공부 기록",
            "계획 ${plan.ordinal} · $CERTIFICATION_NAME",
        )
        OutlinedCard {
            Text("공부 일정", style = MaterialTheme.typography.labelMedium, color = InkBody)
            Text(session?.topic.orEmpty(), style = MaterialTheme.typography.titleMedium, color = Ink)
            if (editor.committed == null) MutedTag(UNREPORTED)
            else Text("저장된 기록: ${checkInSummary(editor.committed)}", style = MaterialTheme.typography.labelMedium, color = InkBody)
            if (session != null) {
                InfoRow("시간", sessionTime(session))
                InfoRow("계획한 공부 시간", PlanGrouping.formatDuration(session.durationMinutes))
            }
        }
        Text("공부는 어땠나요?", style = MaterialTheme.typography.titleMedium, color = Ink)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (status in StudyCheckInStatus.entries) {
                val label = checkInStatusLabel(status)
                ChoiceCard(
                    selected = editor.status == status,
                    onClick = { onStatus(status) },
                    choiceLabel = label,
                    enabled = editing,
                ) {
                    Text(label, style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.weight(1f))
                }
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel("실제로 공부한 시간 (분)")
            OutlinedTextField(
                value = if (editor.status == StudyCheckInStatus.Missed) "0" else editor.minutesText,
                onValueChange = onMinutes,
                enabled = editor.minutesEnabled,
                singleLine = true,
                isError = editor.error == CheckInInputError.InvalidMinutes,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag(ACTUAL_MINUTES_FIELD),
            )
            Note(
                when (editor.status) {
                    null -> "공부 결과를 먼저 골라 주세요."
                    StudyCheckInStatus.Missed -> "못 했어요는 0분으로 기록돼요."
                    else -> "계획한 시간과 달라도 괜찮아요. 더 오래 했다면 그대로 적어 주세요."
                },
            )
        }
        editor.error?.let { Text(inputErrorText(it), style = MaterialTheme.typography.bodyMedium, color = Ink) }
        when (editor.save) {
            CheckInSave.Saving -> LoadingBlock("기록을 저장하는 중이에요", "이 기기에만 저장해요. 서버에 요청을 보내지 않아요.")
            CheckInSave.Unsaved -> AlertCard(
                "공부 기록을 저장하지 못했어요",
                "고른 기록은 화면에 그대로 있지만 아직 저장되지 않았어요. 다시 저장해도 서버에 요청을 보내지 않아요.",
            )
            CheckInSave.Saved -> Note("기록을 저장했어요.")
            CheckInSave.Editing -> Note("기록은 이 기기에만 저장돼요. 계획 일정은 바뀌지 않아요.")
        }
        if (keyboardVisible) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = actions)
        }
    }
}
