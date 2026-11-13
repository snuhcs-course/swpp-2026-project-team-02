package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.domain.ManualMoveRejection
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.PlanSession
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.AdjustmentSelection
import com.swpp.licenseplanner.ui.AdjustmentState
import com.swpp.licenseplanner.ui.PlanStorage
import com.swpp.licenseplanner.ui.adjustmentMessage
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.FieldLabel
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.InputBox
import com.swpp.licenseplanner.ui.components.LoadingBlock
import com.swpp.licenseplanner.ui.components.MutedBox
import com.swpp.licenseplanner.ui.components.NavIcon
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.OutlinedCard
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.message
import com.swpp.licenseplanner.ui.theme.Ink
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/** The field a refused chosen time is shown under. */
internal enum class ManualField { Date, Start }

/** Date problems sit under the date; problems with the whole session sit under the start time. */
internal fun ManualMoveRejection.field(): ManualField? = when (this) {
    ManualMoveRejection.InPast, ManualMoveRejection.BeforePreparationStart -> ManualField.Date
    ManualMoveRejection.ExamBoundExceeded, ManualMoveRejection.StudySessionOverlap,
    ManualMoveRejection.ExternalEventOverlap -> ManualField.Start
    // Not an error: it asks for the one-time confirmation instead.
    ManualMoveRejection.OutsideStudyWindows -> null
}

/**
 * The selected study session whose chosen-time move is open, or null when the adjustment
 * screen itself should be shown (no draft, or the plan is not adjustable as it stands).
 */
internal fun manualSession(plan: SessionPlan, selection: AdjustmentSelection, mode: AdjustmentMode): PlanSession? {
    if (selection.manual == null || mode != AdjustmentMode.Adjustable) return null
    val target = selection.failedScheduleId ?: return null
    return PlanGrouping.sessions(plan.current.schedules).firstOrNull { it.scheduleId == target && !it.isExternal }
}

/** The chosen start, or null while the learner has not chosen a time other than the current one. */
internal fun manualDestination(selection: AdjustmentSelection, session: PlanSession): LocalDateTime? =
    selection.manual?.newStart?.takeIf { it != session.start }

/** `2026-11-24 (화)` for the date field. */
internal fun manualDateText(date: LocalDate): String = "$date (${weekdayLabel(date)})"

/** `11월 24일(화) 14:00–15:00`, with `다음 날` when the session ends after midnight. */
internal fun manualIntervalText(start: LocalDateTime, end: LocalDateTime): String {
    val endDay = end.toLocalDate()
    val endText = when (endDay) {
        start.toLocalDate() -> ""
        start.toLocalDate().plusDays(1) -> "다음 날 "
        else -> "${dayText(endDay)} "
    }
    return "${dayText(start.toLocalDate())} ${WireDateTime.formatTime(start.toLocalTime())}–$endText${WireDateTime.formatTime(end.toLocalTime())}"
}

private fun dayText(date: LocalDate) = "${monthDay(date)}(${weekdayLabel(date)})"

private fun weekdayLabel(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

/**
 * Chosen-time move of one study session (frames 2303:3199, 2303:3265 and the refusal states).
 * The date and start time open the native pickers; the duration stays as it is. The old and new
 * intervals are shown before anything is sent, and a time outside the study windows is sent only
 * after the learner confirms that exact time once. Back or cancel discards the draft and sends
 * nothing; while a request runs, back and every action are held.
 */
@Composable
internal fun ManualMoveScreen(
    plan: SessionPlan,
    session: PlanSession,
    selection: AdjustmentSelection,
    storage: PlanStorage,
    onPick: (LocalDateTime) -> Unit,
    onSubmit: () -> Unit,
    onConfirm: () -> Unit,
    onRevise: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onRetrySave: () -> Unit,
) {
    val state = selection.state
    val loading = state == AdjustmentState.Loading
    val canSend = storage == PlanStorage.Ready
    BackHandler(enabled = !loading, onBack = onCancel)

    val duration = session.durationMinutes
    val destination = manualDestination(selection, session)
    val shown = selection.manual?.newStart ?: session.start
    val error = (state as? AdjustmentState.Failure)?.error
    val rejection = (error as? ApiError.ManualMoveRejected)?.reason
    val errorField = rejection?.field()
    val confirming = selection.canConfirmOutsideAvailability
    var picking by rememberSaveable { mutableStateOf<String?>(null) }

    AppScreen(
        title = "계획 조정",
        navIcon = if (loading) null else NavIcon.Back,
        onNav = onCancel,
        footer = {
            when {
                confirming -> {
                    PrimaryButton("이번 한 번만 옮기기", onConfirm, enabled = canSend)
                    SecondaryButton("시간 다시 고르기", onRevise)
                }
                error != null && rejection == null -> {
                    PrimaryButton("다시 시도", onRetry, enabled = canSend)
                    SecondaryButton("취소", onCancel)
                }
                else -> PrimaryButton(
                    if (loading) "옮기는 중…" else "이 시간으로 옮기기",
                    onSubmit,
                    enabled = !loading && canSend && destination != null && rejection == null,
                    subtitle = if (!loading && destination == null) "날짜나 시작 시간을 바꿔 주세요" else null,
                )
            }
        },
    ) {
        Heading("시간 직접 옮기기", session.topic)
        if (plan.isDemo) {
            MutedBox {
                Text("로컬 시연 계산", style = MaterialTheme.typography.titleSmall, color = Ink)
                Note("이 일정만 기기 안에서 옮겨요. 서버에 저장되지 않아요.")
            }
        }
        StorageAlert(storage, onRetryLoad = {}, onRetrySave = onRetrySave)
        MovePickerField("날짜", manualDateText(shown.toLocalDate()), errorField == ManualField.Date, rejection, !loading) { picking = DATE }
        MovePickerField("시작 시간", WireDateTime.formatTime(shown.toLocalTime()), errorField == ManualField.Start, rejection, !loading) {
            picking = TIME
        }
        Note("기존 공부 시간 · ${duration}분 (그대로 유지)")
        OutlinedCard {
            Note("기존 일정")
            Text(manualIntervalText(session.start, session.end), style = MaterialTheme.typography.bodyLarge, color = Ink)
            Note("옮길 일정")
            Text(
                destination?.let { manualIntervalText(it, it.plusMinutes(duration)) } ?: "아직 고르지 않았어요",
                style = MaterialTheme.typography.bodyLarge,
                color = Ink,
            )
        }
        when {
            loading -> LoadingBlock(
                "일정을 옮기는 중이에요",
                if (plan.isDemo) "로컬 시연 계산이에요." else "결과가 올 때까지 기다려 주세요. 같은 요청을 다시 보내지 않아요.",
            )
            confirming && destination != null -> MutedBox {
                Text("설정한 공부 시간 밖이에요.", style = MaterialTheme.typography.titleSmall, color = Ink)
                Note(
                    "${manualIntervalText(destination, destination.plusMinutes(duration))}에 이번 일정만 옮길까요? " +
                        "설정한 공부 시간은 그대로예요.",
                )
            }
            error != null && rejection == null -> {
                AlertCard("일정을 옮기지 못했어요", error.adjustmentMessage())
                Note("고른 시간은 그대로예요. 다시 시도하면 같은 요청을 다시 보내요.")
            }
        }
    }
    when (picking) {
        DATE -> DatePickerSheet(shown.toLocalDate(), onDismiss = { picking = null }) {
            picking = null
            onPick(LocalDateTime.of(it, shown.toLocalTime()))
        }
        TIME -> StartTimeDialog(shown.toLocalTime(), onDismiss = { picking = null }) {
            picking = null
            onPick(LocalDateTime.of(shown.toLocalDate(), it))
        }
    }
}

/** A labeled picker field; a refusal that concerns it is shown directly underneath. */
@Composable
private fun MovePickerField(
    label: String,
    value: String,
    hasError: Boolean,
    rejection: ManualMoveRejection?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        InputBox(value, onClick, highlighted = hasError, enabled = enabled)
        if (hasError && rejection != null) Text(rejection.message(), style = MaterialTheme.typography.labelMedium, color = Ink)
    }
}

private const val DATE = "date"
private const val TIME = "time"
