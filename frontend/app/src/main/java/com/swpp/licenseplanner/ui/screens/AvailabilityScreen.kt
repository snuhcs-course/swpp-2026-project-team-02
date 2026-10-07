package com.swpp.licenseplanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.R
import com.swpp.licenseplanner.domain.DayAvailability
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.Validation
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.InfoRow
import com.swpp.licenseplanner.ui.components.InputBox
import com.swpp.licenseplanner.ui.components.MutedBox
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.StepperButton
import com.swpp.licenseplanner.ui.theme.Divider
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkSecondary
import java.time.LocalTime

/**
 * FLOW-06, adapted from frame 2005:371: per-weekday duration steppers plus a start-time
 * control. “0시간” means the day is off. No buffer day or readiness claims.
 */
@Composable
fun AvailabilityScreen(
    form: PlanFormState,
    generating: Boolean,
    onEnable: (Weekday, Boolean) -> Unit,
    onStep: (Weekday, Int) -> Unit,
    onStart: (Weekday, LocalTime) -> Unit,
    onBack: () -> Unit,
    onGenerate: () -> Unit,
) {
    var editingStart by rememberSaveable { mutableStateOf<Weekday?>(null) }
    val error = form.availabilityError
    val enabled = Weekday.entries.mapNotNull { day -> form.availability[day]?.takeIf { it.enabled } }

    AppScreen(
        title = "공부 시간",
        onNav = onBack,
        footer = {
            PrimaryButton(
                text = if (generating) "계획을 만드는 중…" else "계획 만들기",
                onClick = onGenerate,
                enabled = error == null && !generating,
            )
        },
    ) {
        Heading("보통 언제 공부할 수 있나요?", "요일마다 시작 시간과 공부 시간을 정해 주세요. 공부하지 않는 요일은 0시간으로 두세요.")
        Column {
            for (day in Weekday.entries) {
                DayRow(
                    day = day,
                    entry = form.availability[day] ?: DayAvailability(),
                    onEnable = { onEnable(day, it) },
                    onStep = { onStep(day, it) },
                    onEditStart = { editingStart = day },
                )
            }
        }
        if (enabled.isNotEmpty()) {
            MutedBox {
                InfoRow("공부하는 요일", "주 ${enabled.size}일")
                InfoRow("공부 시간", "주 ${PlanGrouping.formatDuration(enabled.sumOf { it.durationMinutes }.toLong())}")
            }
        }
        if (error != null) Text(error, style = MaterialTheme.typography.bodyMedium, color = Ink)
    }

    editingStart?.let { day ->
        StartTimeDialog(
            initial = form.availability[day]?.start ?: DayAvailability.DEFAULT_START,
            onDismiss = { editingStart = null },
            onPick = { onStart(day, it); editingStart = null },
        )
    }
}

@Composable
private fun DayRow(
    day: Weekday,
    entry: DayAvailability,
    onEnable: (Boolean) -> Unit,
    onStep: (Int) -> Unit,
    onEditStart: () -> Unit,
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
                if (entry.durationMinutes <= DayAvailability.MIN_DURATION_MINUTES) onEnable(false) else onStep(-1)
            }
            Text(
                if (entry.enabled) PlanGrouping.formatDuration(entry.durationMinutes.toLong()) else "0시간",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(76.dp),
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
            val window = Validation.window(entry)
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 54.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("시작", style = MaterialTheme.typography.bodySmall, color = InkSecondary)
                InputBox(WireDateTime.formatTime(entry.start), onEditStart, Modifier.width(96.dp))
                Text(
                    if (window != null) "~ ${WireDateTime.formatTime(window.end)}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink,
                )
            }
            Validation.windowError(entry)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = Ink, modifier = Modifier.padding(start = 54.dp, bottom = 10.dp))
            }
        }
        HorizontalDivider(color = Divider)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartTimeDialog(initial: LocalTime, onDismiss: () -> Unit, onPick: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("시작 시간", style = MaterialTheme.typography.titleMedium) },
        text = { TimePicker(state = state) },
        confirmButton = { TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text("확인", color = Ink) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = Ink) } },
    )
}
