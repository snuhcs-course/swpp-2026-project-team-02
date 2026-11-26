package com.swpp.licenseplanner.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.TimePickerDialogDefaults
import androidx.compose.material3.TimePickerDisplayMode
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.AvailabilityWindowTarget
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
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.FieldLabel
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.InfoRow
import com.swpp.licenseplanner.ui.components.InputBox
import com.swpp.licenseplanner.ui.components.MutedBox
import com.swpp.licenseplanner.ui.components.OutlinedCard
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.theme.Divider
import com.swpp.licenseplanner.ui.theme.ControlBorder
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkSecondary
import com.swpp.licenseplanner.ui.theme.Paper
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Editing callbacks; a null week edits the recurring base, a Monday edits that week's replacement.
 * [onApply] adds or changes the window on the captured day and adds it to the other given days,
 * returning the overlapping weekdays when nothing was added, an empty list once applied, or null.
 */
class AvailabilityActions(
    val onCapture: (Weekday, Int?, LocalDate?) -> AvailabilityWindowTarget?,
    val onApply: (AvailabilityWindowTarget, LocalTime, Int, Set<Weekday>) -> List<Weekday>?,
    val onRemove: (AvailabilityWindowTarget) -> Boolean,
    val onShowWeek: (LocalDate) -> Unit,
    val onReplaceWeek: (LocalDate) -> Unit,
    val onClearWeek: (LocalDate) -> Unit,
    val onResetWeek: (LocalDate) -> Unit,
)

/**
 * Complete weekday window lists for the recurring base or one replaced preparation week.
 * Each dialog edits a captured list entry and applies its start and duration together.
 */
@Composable
fun AvailabilityScreen(
    form: PlanFormState,
    selectedWeek: LocalDate?,
    generating: Boolean,
    plansSaved: Boolean,
    calendarRead: CalendarReadState,
    actions: AvailabilityActions,
    onBack: () -> Unit,
    onGenerate: () -> Unit,
    onReadCalendar: () -> Unit,
    onOpenCalendarSettings: () -> Unit,
    inputEditSource: Int? = null,
) {
    var editing by rememberSaveable(stateSaver = WindowTargetSaver) { mutableStateOf<AvailabilityWindowTarget?>(null) }
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
    LaunchedEffect(week, form.availability, form.availabilityOverrides) {
        editing?.let { target ->
            val capturedDays = target.week?.let(form.availabilityOverrides::get) ?: form.availability
            if (target.shownWeek != week || capturedDays[target.day]?.windows().orEmpty() != target.windows) editing = null
        }
    }
    val readingCalendar = calendarRead == CalendarReadState.RequestingPermission ||
        calendarRead == CalendarReadState.AwaitingPermission || calendarRead == CalendarReadState.Loading

    AppScreen(
        title = "공부 시간",
        onNav = onBack,
        trailing = { ContextHelpButton(ContextHelpTopic.AVAILABILITY) },
        footer = {
            // Inputs reopened from a plan make another plan; the source plan stays as it is.
            if (inputEditSource != null) Note(inputEditNote(inputEditSource))
            PrimaryButton(
                text = when {
                    generating -> "계획을 만드는 중…"
                    inputEditSource != null -> "새 계획 만들기"
                    else -> "계획 만들기"
                },
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
            else -> Heading("매주 기본 시간", "시작과 종료 시간을 정해 주세요. 캘린더와 겹치는 시간을 제외하고 모두 계획에 써요.")
        }

        if (week != null) {
            val index = weeks.indexOf(week)
            // The week's own actions stay with its dates, ahead of carried-in time and the weekday list.
            OutlinedCard {
                WeekNavigator(
                    week = WeekPlan(week, emptyList()),
                    index = index,
                    count = weeks.size,
                    onPrevious = { resetWeek = null; actions.onShowWeek(weeks[index - 1]) },
                    onNext = { resetWeek = null; actions.onShowWeek(weeks[index + 1]) },
                )
                Text(
                    weekScopeText(source),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (source == WeekSource.REPLACED) {
                    SecondaryButton("이번 주 공부 안 함", { actions.onClearWeek(week) }, enabled = !generating)
                } else {
                    SecondaryButton("이번 주 시간 바꾸기", { actions.onReplaceWeek(week) }, enabled = !generating)
                }
                if (source == WeekSource.BASE) {
                    SecondaryButton("이번 주 공부 안 함", { actions.onClearWeek(week) }, enabled = !generating)
                } else {
                    SecondaryButton(
                        "기본 시간으로 되돌리기",
                        { actions.onResetWeek(week); resetWeek = week.toEpochDay() },
                        enabled = !generating,
                    )
                }
            }
            for (incoming in form.incomingWindows(week)) {
                MutedBox {
                    Text("지난주에서 이어지는 시간", style = MaterialTheme.typography.labelLarge, color = Ink)
                    Text(incomingText(incoming), style = MaterialTheme.typography.bodyMedium, color = InkSecondary)
                }
            }
        }

        if (source != WeekSource.EMPTY) {
            Column {
                for (day in Weekday.entries) {
                    DayRow(
                        day = day,
                        entry = days[day] ?: DayAvailability(),
                        enabled = !generating && !readingCalendar && plansSaved,
                        onEdit = { index -> editing = actions.onCapture(day, index, editWeek) },
                        onRemove = { index -> actions.onCapture(day, index, editWeek)?.let(actions.onRemove) },
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

    editing?.let { target ->
        key(target) {
            StudyWindowDialog(
                target = target,
                onDismiss = { editing = null },
                onConfirm = { start, minutes, days ->
                    // An overlap on any chosen day adds nothing and keeps the dialog open to name it.
                    actions.onApply(target, start, minutes, days).orEmpty().also { if (it.isEmpty()) editing = null }
                },
            )
        }
    }
}

private val WindowTargetSaver = listSaver<AvailabilityWindowTarget?, Any>(
    save = { target ->
        if (target == null) emptyList() else listOf(
            target.day.name, target.week?.toEpochDay() ?: Long.MIN_VALUE,
            target.index ?: -1, target.shownWeek?.toEpochDay() ?: Long.MIN_VALUE,
        ) + target.windows.flatMap { listOf(it.start.toSecondOfDay(), it.durationMinutes) }
    },
    restore = { values ->
        if (values.isEmpty()) null else AvailabilityWindowTarget(
            day = Weekday.valueOf(values[0] as String),
            week = (values[1] as Long).takeUnless { it == Long.MIN_VALUE }?.let(LocalDate::ofEpochDay),
            windows = values.drop(4).chunked(2).map { WindowSpec(LocalTime.ofSecondOfDay((it[0] as Int).toLong()), it[1] as Int) },
            index = (values[2] as Int).takeUnless { it == -1 },
            shownWeek = (values[3] as Long).takeUnless { it == Long.MIN_VALUE }?.let(LocalDate::ofEpochDay),
        )
    },
)

@Composable
private fun DayRow(
    day: Weekday,
    entry: DayAvailability,
    enabled: Boolean,
    onEdit: (Int?) -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${day.label}요일", style = MaterialTheme.typography.titleSmall, color = Ink)
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = { onEdit(null) },
                enabled = enabled,
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(1.5.dp, ControlBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 44.dp)
                    .semantics { contentDescription = "${day.label}요일 시간대 추가" },
            ) { Text("＋ 시간대 추가") }
        }
        entry.windows().forEachIndexed { index, window ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InputBox(
                    "${WireDateTime.formatTime(window.start)} ${endLabel(window)} · ${window.durationMinutes}분",
                    { onEdit(index) },
                    Modifier.weight(1f)
                        .testTag(if (index == 0) "study-duration-${day.key}" else "study-duration-${day.key}-$index")
                        .semantics { contentDescription = windowEditDescription(day, index, window) },
                    enabled = enabled,
                )
                TextButton(
                    onClick = { onRemove(index) },
                    enabled = enabled,
                    modifier = Modifier.semantics { contentDescription = "${day.label}요일 시간대 ${index + 1} 삭제" },
                ) { Text("삭제") }
            }
        }
        HorizontalDivider(color = Divider)
    }
}

/** A typed 24-hour time, or null while the hour or minute is empty or out of range. */
internal fun typedTime(hour: String, minute: String): LocalTime? {
    val h = hour.toIntOrNull()?.takeIf { it in 0..23 } ?: return null
    val m = minute.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
    return LocalTime.of(h, m)
}

/**
 * 24-hour time dialog that opens in typed hour and minute entry; the toggle switches to the dial
 * and back, and both modes show the same time. Only a complete, in-range time can be confirmed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartTimeDialog(initial: LocalTime, onDismiss: () -> Unit, title: String = "시작 시간", onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    var typing by rememberSaveable { mutableStateOf(true) }
    var hourText by rememberSaveable { mutableStateOf("%02d".format(initial.hour)) }
    var minuteText by rememberSaveable { mutableStateOf("%02d".format(initial.minute)) }
    val typed = typedTime(hourText, minuteText)
    fun editTyped(hour: String, minute: String) {
        hourText = hour
        minuteText = minute
        typedTime(hour, minute)?.let {
            state.hour = it.hour
            state.minute = it.minute
        }
    }
    TimePickerDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        modeToggleButton = {
            TimePickerDialogDefaults.DisplayModeToggle(
                onDisplayModeChange = {
                    if (!typing) {
                        hourText = "%02d".format(state.hour)
                        minuteText = "%02d".format(state.minute)
                    }
                    typing = !typing
                },
                displayMode = if (typing) TimePickerDisplayMode.Input else TimePickerDisplayMode.Picker,
                modifier = Modifier.testTag("time-mode-toggle"),
            )
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("study-clock-confirm"),
                onClick = { (if (typing) typed else LocalTime.of(state.hour, state.minute))?.let(onPick) },
                enabled = !typing || typed != null,
            ) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    ) {
        if (typing) {
            TypedTimeFields(hourText, minuteText, onHour = { editTyped(it, minuteText) }, onMinute = { editTyped(hourText, it) })
        } else {
            TimePicker(state = state)
        }
    }
}

/** Two-digit hour and minute boxes; a number outside the 24-hour range is named underneath. */
@Composable
private fun TypedTimeFields(hour: String, minute: String, onHour: (String) -> Unit, onMinute: (String) -> Unit) {
    val hourInvalid = (hour.toIntOrNull() ?: 0) > 23
    val minuteInvalid = (minute.toIntOrNull() ?: 0) > 59
    // A digit typed into a full box starts the number again.
    val digits = { text: String -> text.filter(Char::isDigit).let { if (it.length > 2) it.takeLast(1) else it } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TimeNumberField(hour, { onHour(digits(it)) }, "시", hourInvalid, ImeAction.Next, "time-input-hour")
            Text(":", style = MaterialTheme.typography.displayMedium, color = Ink)
            TimeNumberField(minute, { onMinute(digits(it)) }, "분", minuteInvalid, ImeAction.Done, "time-input-minute")
        }
        if (hourInvalid || minuteInvalid) {
            Text("시는 00~23, 분은 00~59로 입력해 주세요.", style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

@Composable
private fun TimeNumberField(value: String, onValue: (String) -> Unit, label: String, invalid: Boolean, ime: ImeAction, tag: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        singleLine = true,
        isError = invalid,
        textStyle = MaterialTheme.typography.displaySmall.copy(textAlign = TextAlign.Center, color = Ink),
        supportingText = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ime),
        modifier = Modifier.width(96.dp).testTag(tag),
    )
}

/**
 * A complete candidate window stays local until the explicit confirmation. A new window can also
 * go to other weekdays; [onConfirm] returns the overlapping weekdays when nothing was added.
 */
@Composable
private fun StudyWindowDialog(
    target: AvailabilityWindowTarget,
    onDismiss: () -> Unit,
    onConfirm: (LocalTime, Int, Set<Weekday>) -> List<Weekday>,
) {
    val initial = target.index?.let(target.windows::get)
        ?: WindowSpec(DayAvailability.DEFAULT_START, DayAvailability.DEFAULT_DURATION_MINUTES)
    var startSeconds by rememberSaveable { mutableStateOf(initial.start.toSecondOfDay()) }
    var endSeconds by rememberSaveable { mutableStateOf(initial.end.toSecondOfDay()) }
    var pickingStart by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Chosen and overlapping weekdays as bit sets; the opened day is always chosen.
    var chosenDays by rememberSaveable { mutableStateOf(weekdayBits(setOf(target.day))) }
    var overlapDays by rememberSaveable { mutableStateOf(0) }
    val adding = target.index == null
    val chosen = weekdaysOf(chosenDays)
    val overlapping = weekdaysOf(overlapDays)
    val start = LocalTime.ofSecondOfDay(startSeconds.toLong())
    val end = LocalTime.ofSecondOfDay(endSeconds.toLong())
    val minutes = studyWindowMinutes(start, end)
    val daysNote = if (chosen.size > 1) " · ${chosen.joinToString("·") { it.label }} ${chosen.size}일에 추가" else ""
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("공부 시간 입력", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${target.day.label}요일 · ${if (target.week == null) "매주 기본 시간" else "${monthDay(target.week)} 주"}", style = MaterialTheme.typography.labelLarge, color = Ink)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FieldLabel("시작 시간")
                        InputBox(WireDateTime.formatTime(start), { pickingStart = true }, Modifier.testTag("study-window-start"))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        FieldLabel("종료 시간")
                        InputBox(WireDateTime.formatTime(end), { pickingStart = false }, Modifier.testTag("study-window-end"))
                    }
                }
                if (adding) {
                    FieldLabel("같은 시간에 공부할 요일")
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (day in Weekday.entries) {
                            WeekdayChip(
                                day = day,
                                selected = day in chosen,
                                fixed = day == target.day,
                                overlapping = day in overlapping,
                                onToggle = {
                                    chosenDays = chosenDays xor weekdayBits(setOf(day))
                                    overlapDays = 0
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    if (overlapping.isNotEmpty()) {
                        AlertCard(
                            weekdayOverlapTitle(overlapping),
                            "다른 공부 시간과 겹쳐서 아무 요일에도 추가하지 않았어요. 시간이나 요일을 바꿔 주세요.",
                        )
                    }
                    Note("${target.day.fullLabel}은 항상 포함돼요. 다른 요일을 눌러 같은 시간을 함께 추가해요.")
                }
                when {
                    minutes == null -> Note("시작 시간과 종료 시간을 다르게 정해 주세요.")
                    end.isBefore(start) -> Note("다음 날 종료 · ${PlanGrouping.formatDuration(minutes.toLong())}$daysNote")
                    else -> Note("공부 시간 ${PlanGrouping.formatDuration(minutes.toLong())}$daysNote")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { minutes?.let { overlapDays = weekdayBits(onConfirm(start, it, chosen.toSet()).toSet()) } },
                enabled = minutes != null,
            ) { Text("확인") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
    pickingStart?.let { isStart ->
        StartTimeDialog(
            initial = if (isStart) start else end,
            onDismiss = { pickingStart = null },
            title = if (isStart) "시작 시간" else "종료 시간",
            onPick = { time ->
                if (isStart) startSeconds = time.toSecondOfDay() else endSeconds = time.toSecondOfDay()
                pickingStart = null
                overlapDays = 0
            },
        )
    }
}

/** One weekday toggle of the add dialog; the opened day stays on, and an overlapping day is marked. */
@Composable
private fun WeekdayChip(
    day: Weekday,
    selected: Boolean,
    fixed: Boolean,
    overlapping: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
) {
    val shape = MaterialTheme.shapes.small
    Box(modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .clip(shape)
                .toggleable(value = selected, enabled = !fixed, role = Role.Checkbox, onValueChange = { onToggle() })
                .testTag("window-day-${day.key}")
                .semantics {
                    contentDescription = day.fullLabel + if (overlapping) ", 겹침" else ""
                    if (fixed) stateDescription = "항상 포함"
                },
            shape = shape,
            color = if (selected) Ink else Paper,
            contentColor = if (selected) Paper else Ink,
            border = BorderStroke(1.5.dp, if (selected) Ink else ControlBorder),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(day.label, style = MaterialTheme.typography.labelLarge)
            }
        }
        if (overlapping) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(16.dp)
                    .clip(CircleShape).background(Paper).border(1.dp, Ink, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text("!", style = MaterialTheme.typography.labelSmall, color = Ink) }
        }
    }
}

private fun weekdayBits(days: Set<Weekday>): Int = days.fold(0) { bits, day -> bits or (1 shl day.ordinal) }

private fun weekdaysOf(bits: Int): List<Weekday> = Weekday.entries.filter { bits and (1 shl it.ordinal) != 0 }

/** The note that the source plan stays as it is; 이, 사, 오 and 구 end in a vowel, so they take 는. */
internal fun inputEditNote(source: Int): String =
    "계획 $source${if (source % 10 in setOf(2, 4, 5, 9)) "는" else "은"} 그대로 두고 새 계획을 만들어요."

/** “수요일과 겹쳐요”, or “수요일, 금요일과 겹쳐요”; every weekday name ends in 일, which takes 과. */
internal fun weekdayOverlapTitle(days: List<Weekday>): String = days.joinToString(", ") { it.fullLabel } + "과 겹쳐요"

/** Whether the shown week follows the base, has its own hours, or has none. */
internal fun weekScopeText(source: WeekSource): String = when (source) {
    WeekSource.BASE -> "이번 주는 기본 시간을 사용해요."
    WeekSource.REPLACED -> "이번 주는 이 주에만 정한 시간을 사용해요. 다른 주는 그대로예요."
    WeekSource.EMPTY -> "이번 주는 공부하지 않도록 정했어요. 다른 주는 그대로예요."
}

/** Earlier end times belong to the following day; equal times cannot form a study window. */
internal fun studyWindowMinutes(start: LocalTime, end: LocalTime): Int? {
    val minutes = Duration.between(start, end).toMinutes().toInt()
    val duration = if (minutes < 0) minutes + 1440 else minutes
    return duration.takeIf(WindowSpec::isValidDuration)
}

/** Spoken label of a window's edit control: “월요일 시간대 2, 19:00–21:00, 120분, 수정”. */
internal fun windowEditDescription(day: Weekday, index: Int, window: WindowSpec): String =
    "${day.label}요일 시간대 ${index + 1}, ${WireDateTime.formatTime(window.start)}–" +
        (if (window.endsNextDay) "다음 날 " else "") + "${WireDateTime.formatTime(window.end)}, ${window.durationMinutes}분, 수정"

/** “– 20:00”, or “– 다음 날 00:30” for an end on the following date. */
internal fun endLabel(window: WindowSpec): String =
    "– " + (if (window.endsNextDay) "다음 날 " else "") + WireDateTime.formatTime(window.end)

internal fun windowRangeLabel(window: WindowSpec): String =
    WireDateTime.formatTime(window.start) + "–" +
        (if (window.endsNextDay) "다음 날 " else "") + WireDateTime.formatTime(window.end)

internal fun dateAvailabilityLabel(context: StudyAvailability?, date: LocalDate): String {
    if (context == null) return "공부 가능 시간 정보가 없어요."
    val windows = context.windowsStarting(date)
    return if (windows.isEmpty()) "공부 가능 시간 없음"
    else "공부 가능 ${windows.joinToString(" · ", transform = ::windowRangeLabel)}"
}

internal fun originalAvailabilityLabels(context: StudyAvailability?): List<String> {
    if (context == null) return listOf("공부 가능 시간 정보가 없어요.")
    fun labels(week: WeekWindows): String = Weekday.entries.mapNotNull { day ->
        week[day]?.takeIf { it.isNotEmpty() }?.let { windows ->
            "${day.label} ${windows.joinToString(" · ", transform = ::windowRangeLabel)}"
        }
    }.joinToString(" / ").ifEmpty { "공부 가능 시간 없음" }
    return listOf("공부 가능 시간: ${labels(context.base)}") + context.overrides.toSortedMap().map { (monday, week) ->
        "${overridesLabel(mapOf(monday to week))}: ${labels(week)}"
    }
}

internal data class WeekTotals(val days: Int, val minutes: Int)

/** Weekdays with any window and the minutes of every window. */
internal fun weekTotals(week: WeekWindows): WeekTotals =
    WeekTotals(week.count { it.value.isNotEmpty() }, week.values.sumOf { windows -> windows.sumOf { it.durationMinutes } })

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
            "시작 시간이나 종료 시간을 바꿔 주세요.",
    )
}

/** A window from the previous week that runs into the shown week. */
internal fun incomingText(interval: StudyInterval): String =
    "${dayWithWeekday(interval.start)} ${WireDateTime.formatTime(interval.start.toLocalTime())}–" +
        "${WireDateTime.formatTime(interval.end.toLocalTime())} 공부 시간이 지난주 설정에서 이어져요."

private fun dayWithWeekday(at: LocalDateTime): String =
    "${monthDay(at.toLocalDate())}(${Weekday.of(at.dayOfWeek).label})"
