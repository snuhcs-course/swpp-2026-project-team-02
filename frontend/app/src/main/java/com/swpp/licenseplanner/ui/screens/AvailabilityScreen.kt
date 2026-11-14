package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.swpp.licenseplanner.R
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.StudyAvailability
import com.swpp.licenseplanner.domain.StudyInterval
import com.swpp.licenseplanner.domain.WeekPlan
import com.swpp.licenseplanner.domain.WeekSource
import com.swpp.licenseplanner.domain.WeekWindows
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WindowSpec
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.domain.toWeekWindows
import com.swpp.licenseplanner.ui.CalendarReadState
import com.swpp.licenseplanner.ui.ProfileState
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.FieldLabel
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.InfoRow
import com.swpp.licenseplanner.ui.components.InputBox
import com.swpp.licenseplanner.ui.components.MutedBox
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.OutlinedCard
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.components.StepperButton
import com.swpp.licenseplanner.ui.theme.Divider
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkSecondary
import com.swpp.licenseplanner.ui.theme.Paper
import com.swpp.licenseplanner.ui.userMessage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Editing callbacks; a null week edits the recurring base, a Monday edits that week's replacement. */
class AvailabilityActions(
    val onEnable: (Weekday, Boolean, LocalDate?) -> Unit,
    val onStep: (Weekday, Int, LocalDate?) -> Unit,
    val onStart: (Weekday, LocalTime, LocalDate?) -> Unit,
    val onDuration: (Weekday, Int, LocalDate?) -> Unit,
    val onShowWeek: (LocalDate) -> Unit,
    val onReplaceWeek: (LocalDate) -> Unit,
    val onClearWeek: (LocalDate) -> Unit,
    val onResetWeek: (LocalDate) -> Unit,
)

/** Explicit shared-profile callbacks (live only). */
class ProfileActions(
    val onLoad: () -> Unit,
    val onSave: () -> Unit,
    val onAccept: () -> Unit,
    val onDismiss: () -> Unit,
)

/**
 * One visible window per weekday (start plus duration, possibly ending the next day)
 * for the recurring base or one replaced week of the
 * preparation period, with a retained-window count for windows this editor does not show.
 * [profile] is null when the build has no server profile (demo); otherwise the live load-preview
 * and save actions appear with the shared-storage notice.
 */
@Composable
fun AvailabilityScreen(
    form: PlanFormState,
    selectedWeek: LocalDate?,
    generating: Boolean,
    plansSaved: Boolean,
    calendarRead: CalendarReadState,
    profile: ProfileState?,
    actions: AvailabilityActions,
    profileActions: ProfileActions,
    onBack: () -> Unit,
    onGenerate: () -> Unit,
    onReadCalendar: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
) {
    when (profile) {
        is ProfileState.Preview -> return ProfilePreview(profile.availability, generating, profileActions)
        is ProfileState.Failure -> return ProfileFailure(profile, form.studyAvailability, profileActions)
        else -> Unit
    }

    var editingStart by rememberSaveable { mutableStateOf<Weekday?>(null) }
    var editingDuration by rememberSaveable { mutableStateOf<Weekday?>(null) }
    // The week whose replacement was just removed, for the confirmation line.
    var resetWeek by rememberSaveable { mutableStateOf<Long?>(null) }

    val error = form.availabilityError
    val conflict = if (error != null) conflictText(form.studyAvailability) else null
    val weeks = form.studyWeeks
    val week = form.shownWeek(selectedWeek)
    val source = week?.let(form::weekSource) ?: WeekSource.BASE
    // The editor edits the shown week's replacement, or the base when the week inherits it.
    val editWeek = week?.takeIf { source == WeekSource.REPLACED }
    val days = if (editWeek != null) form.availabilityOverrides.getValue(editWeek) else form.availability
    val readingCalendar = calendarRead == CalendarReadState.RequestingPermission ||
        calendarRead == CalendarReadState.AwaitingPermission || calendarRead == CalendarReadState.Loading

    AppScreen(
        title = "공부 시간",
        onNav = onBack,
        footer = {
            PrimaryButton(
                text = if (generating) "계획을 만드는 중…" else "계획 만들기",
                onClick = onGenerate,
                enabled = error == null && !generating && !readingCalendar && plansSaved,
                // A new plan joins the saved list, so that list must be read and saved first.
                subtitle = if (!generating && !plansSaved) "저장된 계획을 확인한 뒤 만들 수 있어요" else null,
            )
        },
    ) {
        when {
            conflict != null -> Heading("공부 시간을 확인해 주세요", "입력한 시간을 확인하고 겹치는 시간대를 바꿔 주세요.")
            source == WeekSource.REPLACED -> Heading("이번 주 공부 시간", "이 주에만 적용돼요. 다른 주의 공부 시간은 그대로예요.")
            source == WeekSource.EMPTY -> Heading("이번 주 공부 안 함", "이 주에 시작하는 공부 시간이 없어요.")
            week != null && resetWeek == week.toEpochDay() ->
                Heading("이번 주 공부 시간", "${monthDay(week)} 주의 별도 설정을 지웠어요. 이제 기본 시간을 사용해요.")
            else -> Heading("보통 언제 공부할 수 있나요?", "요일마다 시작 시간과 공부할 분을 정해 주세요.")
        }

        if (week != null) {
            val index = weeks.indexOf(week)
            WeekNavigator(
                week = WeekPlan(week, emptyList()),
                index = index,
                count = weeks.size,
                onPrevious = { resetWeek = null; actions.onShowWeek(weeks[index - 1]) },
                onNext = { resetWeek = null; actions.onShowWeek(weeks[index + 1]) },
            )
            for (incoming in form.incomingWindows(week)) {
                MutedBox {
                    Text("지난주에서 이어지는 시간", style = MaterialTheme.typography.labelLarge, color = Ink)
                    Text(incomingText(incoming), style = MaterialTheme.typography.bodyMedium, color = InkSecondary)
                }
            }
            when (source) {
                WeekSource.BASE -> {
                    Note("이번 주는 기본 시간을 사용해요.")
                    SecondaryButton("이번 주 시간 바꾸기", { actions.onReplaceWeek(week) }, enabled = !generating)
                    SecondaryButton("이번 주 공부 안 함", { actions.onClearWeek(week) }, enabled = !generating)
                }
                WeekSource.REPLACED -> {
                    Note("이번 주에만 적용하는 시간이에요.")
                    SecondaryButton("이번 주 공부 안 함", { actions.onClearWeek(week) }, enabled = !generating)
                    SecondaryButton(
                        "기본 시간으로 되돌리기",
                        { actions.onResetWeek(week); resetWeek = week.toEpochDay() },
                        enabled = !generating,
                    )
                }
                WeekSource.EMPTY -> {
                    SecondaryButton("이번 주 시간 바꾸기", { actions.onReplaceWeek(week) }, enabled = !generating)
                    SecondaryButton(
                        "기본 시간으로 되돌리기",
                        { actions.onResetWeek(week); resetWeek = week.toEpochDay() },
                        enabled = !generating,
                    )
                }
            }
        }

        if (source != WeekSource.EMPTY) {
            if (week != null && editWeek == null) Heading("매주 기본 시간")
            Column {
                for (day in Weekday.entries) {
                    DayRow(
                        day = day,
                        entry = days[day] ?: DayAvailability(),
                        onEnable = { actions.onEnable(day, it, editWeek) },
                        onStep = { actions.onStep(day, it, editWeek) },
                        onEditStart = { editingStart = day },
                        onEditDuration = { editingDuration = day },
                    )
                }
            }
            val totals = weekTotals(days.toWeekWindows())
            if (totals.days > 0) {
                MutedBox {
                    InfoRow("공부하는 요일", "${totals.days}일")
                    InfoRow("공부 시간", PlanGrouping.formatDuration(totals.minutes.toLong()))
                }
            }
        }
        when {
            conflict != null -> AlertCard(conflict.title, conflict.body)
            error != null -> Text(error, style = MaterialTheme.typography.bodyMedium, color = Ink)
        }

        if (profile != null) {
            Heading("서버 공부 시간", SHARED_PROFILE_NOTICE)
            val profileBusy = profile == ProfileState.Loading || profile == ProfileState.Saving
            SecondaryButton(
                if (profile == ProfileState.Loading) "불러오는 중…" else "저장된 공부 시간 불러오기",
                profileActions.onLoad,
                enabled = !profileBusy && !generating,
            )
            SecondaryButton(
                if (profile == ProfileState.Saving) "저장하는 중…" else "서버에 공부 시간 저장",
                profileActions.onSave,
                enabled = !profileBusy && !generating && error == null,
            )
            if (profile == ProfileState.Saved) Note("서버에 공부 시간을 저장했어요. 이미 만든 계획은 바뀌지 않아요.")
        }

        Heading(
            "휴대폰 캘린더",
            "준비 기간의 개인 일정 시간을 읽어 공부 계획과 겹치지 않게 합니다. 일정 제목은 읽거나 전송하지 않습니다.",
        )
        when (calendarRead) {
            CalendarReadState.Idle -> SecondaryButton("캘린더 일정 불러오기", onReadCalendar)
            CalendarReadState.RequestingPermission,
            CalendarReadState.AwaitingPermission,
            CalendarReadState.Loading,
            -> SecondaryButton("캘린더 일정을 읽는 중…", onReadCalendar, enabled = false)
            is CalendarReadState.Loaded -> {
                MutedBox {
                    InfoRow("반영한 일정", "${calendarRead.count}개")
                    if (calendarRead.recurringOccurrences > 0) {
                        InfoRow("반복 일정 발생분", "${calendarRead.recurringOccurrences}개")
                    }
                    if (calendarRead.allDayOccurrences > 0) {
                        InfoRow("종일 일정", "${calendarRead.allDayOccurrences}개")
                    }
                    Text(
                        "반복 일정은 준비 기간의 각 발생분을, 종일 일정은 해당 날짜 전체를 바쁜 시간으로 반영합니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSecondary,
                    )
                }
                SecondaryButton("캘린더 일정 다시 불러오기", onReadCalendar)
            }
            CalendarReadState.Empty -> {
                MutedBox {
                    Text("준비 기간에 겹치는 캘린더 일정이 없습니다.", style = MaterialTheme.typography.bodyMedium, color = Ink)
                }
                SecondaryButton("캘린더 일정 다시 불러오기", onReadCalendar)
            }
            CalendarReadState.PermissionDenied -> {
                AlertCard(
                    "캘린더가 반영되지 않았어요",
                    "캘린더 읽기 권한이 없습니다. 권한 없이 계획을 만들거나 설정에서 권한을 허용한 뒤 다시 불러올 수 있습니다.",
                )
                SecondaryButton("권한 다시 요청하기", onReadCalendar)
                SecondaryButton("앱 설정 열기", onOpenCalendarSettings)
            }
            CalendarReadState.Unavailable -> {
                AlertCard(
                    "캘린더를 읽지 못했어요",
                    "캘린더는 반영되지 않았습니다. 계획은 그대로 만들 수 있으며, 잠시 후 다시 시도할 수 있습니다.",
                )
                SecondaryButton("다시 시도하기", onReadCalendar)
            }
        }
    }

    editingStart?.let { day ->
        StartTimeDialog(
            initial = days[day]?.start ?: DayAvailability.DEFAULT_START,
            onDismiss = { editingStart = null },
            onPick = { actions.onStart(day, it, editWeek); editingStart = null },
        )
    }
    editingDuration?.let { day ->
        val entry = days[day] ?: DayAvailability()
        DurationDialog(
            day = day,
            start = entry.start,
            initial = entry.durationMinutes.takeIf { entry.enabled },
            onDismiss = { editingDuration = null },
            onConfirm = { actions.onDuration(day, it, editWeek); editingDuration = null },
        )
    }
}

private const val SHARED_PROFILE_NOTICE = "서버의 공용 공부 시간이에요. 다른 사용자가 바꿀 수 있고, 이미 만든 계획은 바뀌지 않아요."

@Composable
private fun ProfilePreview(preview: StudyAvailability, generating: Boolean, actions: ProfileActions) {
    BackHandler(onBack = actions.onDismiss)
    AppScreen(title = "공부 시간", onNav = actions.onDismiss) {
        Heading("저장된 공부 시간 미리보기", SHARED_PROFILE_NOTICE)
        ProfileSummary(preview, firstLabel = "기본 시간")
        Note("적용하면 현재 입력한 기본 시간과 주별 설정을 서버에서 불러온 내용으로 바꿔요.")
        PrimaryButton("불러온 시간 적용", actions.onAccept, enabled = !generating)
        SecondaryButton("취소", actions.onDismiss)
    }
}

@Composable
private fun ProfileFailure(failure: ProfileState.Failure, current: StudyAvailability, actions: ProfileActions) {
    BackHandler(onBack = actions.onDismiss)
    AppScreen(title = "공부 시간", onNav = actions.onDismiss) {
        Heading(if (failure.saving) "서버에 공부 시간 저장" else "저장된 공부 시간 불러오기", SHARED_PROFILE_NOTICE)
        ProfileSummary(current, firstLabel = "현재 입력")
        AlertCard(
            if (failure.saving) "공부 시간을 저장하지 못했어요." else "공부 시간을 불러오지 못했어요.",
            "입력한 공부 시간과 추가 시간은 그대로예요. ${failure.error.userMessage()}",
        )
        PrimaryButton("다시 시도", if (failure.saving) actions.onSave else actions.onLoad)
        SecondaryButton("취소", actions.onDismiss)
    }
}

@Composable
private fun ProfileSummary(availability: StudyAvailability, firstLabel: String) {
    OutlinedCard {
        InfoRow(firstLabel, baseLabel(availability.base))
        InfoRow("별도 설정", overridesLabel(availability.overrides))
        InfoRow("추가 공부 시간", "${retainedWindowCount(availability)}개")
    }
}

@Composable
private fun DayRow(
    day: Weekday,
    entry: DayAvailability,
    onEnable: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
    onEditStart: () -> Unit,
    onEditDuration: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(day.label, style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.width(44.dp))
            Spacer(Modifier.weight(1f))
            StepperButton(R.drawable.ic_minus, "${day.label}요일 시간 줄이기", enabled = entry.enabled) {
                if (entry.durationMinutes <= DayAvailability.STEP_MINUTES) onEnable(false) else onStep(-1)
            }
            InputBox(
                if (entry.enabled) "${entry.durationMinutes}분" else "0분",
                onEditDuration,
                Modifier.width(92.dp).testTag("study-duration-${day.key}"),
            )
            StepperButton(
                R.drawable.ic_plus,
                "${day.label}요일 시간 늘리기",
                enabled = !entry.enabled || entry.durationMinutes < DayAvailability.MAX_DURATION_MINUTES,
            ) {
                if (entry.enabled) onStep(1) else onEnable(true)
            }
        }
        if (entry.enabled) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 54.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("시작", style = MaterialTheme.typography.bodySmall, color = InkSecondary)
                InputBox(WireDateTime.formatTime(entry.start), onEditStart, Modifier.width(96.dp))
                Text(
                    if (WindowSpec.isValidDuration(entry.durationMinutes)) endLabel(WindowSpec(entry.start, entry.durationMinutes)) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                )
            }
        }
        if (entry.extras.isNotEmpty()) {
            Note("추가 공부 시간 ${entry.extras.size}개 유지", Modifier.padding(bottom = 10.dp))
        }
        HorizontalDivider(color = Divider)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartTimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("시작 시간", style = MaterialTheme.typography.titleMedium) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("확인", color = Ink) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = Ink) } },
    )
}

/** Numeric entry for the whole supported range, showing the derived end before it is applied. */
@Composable
private fun DurationDialog(day: Weekday, start: LocalTime, initial: Int?, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial?.toString().orEmpty()) }
    val minutes = parseDurationMinutes(text)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, color = Paper) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Heading("공부 시간 입력")
                Text("${day.label}요일 · 시작 ${WireDateTime.formatTime(start)}", style = MaterialTheme.typography.labelLarge, color = Ink)
                FieldLabel("공부 시간 (분)")
                OutlinedTextField(
                    value = text,
                    onValueChange = { value -> text = value.filter(Char::isDigit).take(4) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Note("1~1439분을 입력해 주세요.")
                if (minutes != null) Note("종료: ${endLabel(WindowSpec(start, minutes)).removePrefix("– ")}")
                PrimaryButton("확인", { minutes?.let(onConfirm) }, enabled = minutes != null)
                SecondaryButton("취소", onDismiss)
            }
        }
    }
}

/** Minutes typed in the duration field, or null unless a whole number within 1–1439. */
internal fun parseDurationMinutes(text: String): Int? =
    text.toIntOrNull()?.takeIf { WindowSpec.isValidDuration(it) }

/** “– 20:00”, or “– 다음 날 00:30” for an end on the following date. */
internal fun endLabel(window: WindowSpec): String =
    "– " + (if (window.endsNextDay) "다음 날 " else "") + WireDateTime.formatTime(window.end)

internal data class WeekTotals(val days: Int, val minutes: Int)

/** Weekdays with any window and the minutes of every window, unshown ones included. */
internal fun weekTotals(week: WeekWindows): WeekTotals =
    WeekTotals(week.count { it.value.isNotEmpty() }, week.values.sumOf { windows -> windows.sumOf { it.durationMinutes } })

/** Windows the editor keeps without showing: all but the first of each day in the base and every replacement week. */
internal fun retainedWindowCount(availability: StudyAvailability): Int =
    (listOf(availability.base) + availability.overrides.values).sumOf { week -> week.values.sumOf { (it.size - 1).coerceAtLeast(0) } }

internal fun baseLabel(base: WeekWindows): String =
    weekTotals(base).let { if (it.days == 0) "없음" else "주 ${it.days}일 · ${PlanGrouping.formatDuration(it.minutes.toLong())}" }

/** One replaced or empty week by its Monday, or how many weeks have their own times. */
internal fun overridesLabel(overrides: Map<LocalDate, WeekWindows>): String = when (overrides.size) {
    0 -> "없음"
    1 -> overrides.entries.single().let { (monday, week) ->
        "${monthDay(monday)} 주 · " + if (week.values.all { it.isEmpty() }) "공부 안 함" else "시간 바꿈"
    }
    else -> "${overrides.size}개 주"
}

internal data class ConflictText(val title: String, val body: String)

/** The first overlap of the context as the days involved and the overlapping time, or null. */
internal fun conflictText(availability: StudyAvailability): ConflictText? {
    val (first, second) = availability.firstOverlap() ?: return null
    val earlier = Weekday.of(first.start.dayOfWeek)
    val later = Weekday.of(second.start.dayOfWeek)
    val title = if (earlier == later) "${earlier.label}요일 공부 시간이 겹쳐요." else "${earlier.label}요일과 ${later.label}요일 공부 시간이 겹쳐요."
    val from = second.start
    val until = minOf(first.end, second.end)
    // Replacement-week overlaps name their week; base overlaps repeat every week.
    val week = listOf(first, second).map { StudyAvailability.mondayOf(it.start.toLocalDate()) }.firstOrNull { it in availability.overrides }
    val where = week?.let { "${monthDay(it)} 주 " }.orEmpty()
    val untilText = (if (until.toLocalDate().isAfter(from.toLocalDate())) "다음 날 " else "") + WireDateTime.formatTime(until.toLocalTime())
    return ConflictText(
        title,
        "$where${Weekday.of(from.dayOfWeek).label}요일 ${WireDateTime.formatTime(from.toLocalTime())}–${untilText}이 겹쳐요. " +
            "시작 시간이나 공부할 분을 바꿔 주세요.",
    )
}

/** A window from the previous week that runs into the shown week. */
internal fun incomingText(interval: StudyInterval): String =
    "${dayWithWeekday(interval.start)} ${WireDateTime.formatTime(interval.start.toLocalTime())}–" +
        "${WireDateTime.formatTime(interval.end.toLocalTime())} 공부 시간이 지난주 설정에서 이어져요."

private fun dayWithWeekday(at: LocalDateTime): String =
    "${monthDay(at.toLocalDate())}(${Weekday.of(at.dayOfWeek).label})"
