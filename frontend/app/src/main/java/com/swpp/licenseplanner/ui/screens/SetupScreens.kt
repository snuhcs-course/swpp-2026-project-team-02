package com.swpp.licenseplanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.domain.CERTIFICATION_ID
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.CurrentLevel
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.ChoiceCard
import com.swpp.licenseplanner.ui.components.FieldLabel
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.InfoRow
import com.swpp.licenseplanner.ui.components.InputBox
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.OutlinedCard
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkSecondary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** FLOW-01: the only supported certification; continue stays disabled until it is selected. */
@Composable
fun CertificationScreen(form: PlanFormState, onSelect: (String) -> Unit, onContinue: () -> Unit) {
    val selected = form.certificationId == CERTIFICATION_ID
    AppScreen(
        title = "자격증",
        navIcon = null,
        footer = { PrimaryButton("다음", onContinue, enabled = selected) },
    ) {
        Heading("어떤 시험을 준비하나요?", "지금은 컴퓨터활용능력 2급만 지원해요.")
        ChoiceCard(selected = selected, onClick = { onSelect(CERTIFICATION_ID) }) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(CERTIFICATION_NAME, style = MaterialTheme.typography.titleMedium, color = Ink)
                Text("필기", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
            }
        }
    }
}

/**
 * FLOW-02, adapted from frame 2004:479 for the written-only prototype (design D14): the written
 * exam card plus preparation start and written exam date fields. The preparation period is
 * shown once the dates are valid.
 */
@Composable
fun DatesScreen(
    form: PlanFormState,
    onStartChange: (LocalDate) -> Unit,
    onExamChange: (LocalDate) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val days = form.preparationDays
    AppScreen(
        title = CERTIFICATION_NAME,
        onNav = onBack,
        footer = { PrimaryButton("다음", onContinue, enabled = days != null) },
    ) {
        Heading("필기 시험 준비", "이 프로토타입은 필기 시험 준비만 다뤄요. 실기 준비는 포함하지 않아요.")
        OutlinedCard {
            Text("필기", style = MaterialTheme.typography.titleMedium, color = Ink)
            InfoRow("과목", "컴퓨터 일반, 스프레드시트 일반")
            InfoRow("형식", "객관식 40문항, 40분")
            InfoRow("합격 기준", "평균 60점 이상, 과목별 40점 이상")
            InfoRow("시험일", "상시 (CBT), 원하는 회차 접수")
        }
        DateField("준비 시작일", form.preparationStart) { picking = START }
        DateField("필기 시험일", form.examDate) { picking = EXAM }
        val error = form.datesError
        when {
            days != null -> Text("준비 기간 ${days}일", style = MaterialTheme.typography.titleSmall, color = Ink)
            form.examDate != null && error != null -> Text(error, style = MaterialTheme.typography.bodyMedium, color = Ink)
            else -> Note("필기 시험일을 고르면 준비 기간을 알려 드려요.")
        }
    }
    when (picking) {
        START -> DatePickerSheet(form.preparationStart, onDismiss = { picking = null }) { onStartChange(it); picking = null }
        EXAM -> DatePickerSheet(form.examDate ?: form.preparationStart.plusDays(1), onDismiss = { picking = null }) {
            onExamChange(it); picking = null
        }
    }
}

private const val START = "start"
private const val EXAM = "exam"

@Composable
private fun DateField(label: String, date: LocalDate?, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label)
        InputBox(date?.toString() ?: "날짜 선택", onClick)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickerSheet(initial: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }) { Text("확인", color = Ink) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소", color = Ink) } },
    ) {
        DatePicker(state = state)
    }
}

/** FLOW-03: three level choices; each carries the fixed self-assessment sentence. */
@Composable
fun LevelScreen(form: PlanFormState, onSelect: (CurrentLevel) -> Unit, onBack: () -> Unit, onContinue: () -> Unit) {
    AppScreen(
        title = "현재 실력",
        onNav = onBack,
        footer = { PrimaryButton("예시 문항 풀기", onContinue, enabled = form.level != null) },
    ) {
        Heading("엑셀은 어느 정도 다뤄 봤나요?", "고른 설명은 자기 평가 문장으로 계획 요청에 함께 보내요.")
        for (level in CurrentLevel.entries) {
            ChoiceCard(selected = form.level == level, onClick = { onSelect(level) }) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(level.label, style = MaterialTheme.typography.titleSmall, color = Ink)
                    Text(level.selfAssessment, style = MaterialTheme.typography.bodySmall, color = InkSecondary)
                }
            }
        }
    }
}
