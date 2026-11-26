package com.swpp.licenseplanner.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.licenseplanner.domain.*
import java.time.LocalDate

private val Green = Color(0xFF2B8050)
private val Pale = Color(0xFFF0F7F2)

@Composable
internal fun CertificateCard(selected: Boolean, onSelect: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = if (selected) Pale else Color.White,
        border = BorderStroke(1.dp, if (selected) Green else Color(0xFFE8E8EB))) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 13.dp, vertical = 19.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(38.dp, 44.dp).background(Color(0xFFEEF2EE), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Text("▦", color = Green, fontSize = 23.sp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("사무·정보기술", style = MaterialTheme.typography.labelSmall)
                Text(CERTIFICATION_NAME, style = MaterialTheme.typography.titleMedium)
                Text("필기 · 컴퓨터 일반, 스프레드시트 일반", style = MaterialTheme.typography.bodySmall)
            }
            Text(if (selected) "✓" else "○", color = Green)
        }
    }
}

@Composable
internal fun CreationHeading(step: Int, title: String, description: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("자격증 선택", "진단 평가", "시간·날짜 설정").forEachIndexed { i, label ->
            val active = i + 1 == step
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(22.dp).background(if (active) Green else Pale, CircleShape), contentAlignment = Alignment.Center) {
                    Text(if (i + 1 < step) "✓" else (i + 1).toString(), color = if (active) Color.White else Green, fontSize = 10.sp)
                }
                Text(label, fontSize = 11.sp)
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("STEP 0$step", color = Green, fontSize = 11.sp)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CreationOption(label: String, selected: Boolean, select: () -> Unit) {
    Surface(shape = RoundedCornerShape(10.dp), color = if (selected) Pale else Color.White,
        border = BorderStroke(1.dp, if (selected) Green else Color(0xFFE6E6E9))) {
        Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = select).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RadioButton(selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Green))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun TimeSelectionGrid(form: PlanFormState, week: LocalDate?, enabled: Boolean,
    paint: (Weekday, Int, LocalDate?, Boolean) -> Unit) {
    var mode by rememberSaveable { mutableIntStateOf(0) }
    val base = form.availability
    val effective = form.availabilityOverrides[week] ?: base
    val days = if (mode == 0) base else effective
    val labels = listOf("주간 선호", "이번 주 추가", "이번 주 제외")
    Text("공부 선호 시간", style = MaterialTheme.typography.titleMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        labels.forEachIndexed { index, label ->
            FilterChip(selected = mode == index, enabled = enabled && (index == 0 || week != null),
                onClick = { mode = index }, label = { Text(label, fontSize = 11.sp) })
        }
    }
    Text("칸을 누르거나 길게 눌러 드래그하세요. 한 칸은 30분이에요.", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth()) {
        Spacer(Modifier.width(44.dp))
        Weekday.entries.forEach { day ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(day.label) }
        }
    }
    val list = rememberLazyListState()
    val currentDays by rememberUpdatedState(days)
    LazyColumn(Modifier.fillMaxWidth().height(320.dp).pointerInput(mode, week, enabled) {
        if (!enabled || (mode != 0 && week == null)) return@pointerInput
        val gutter = 44.dp.toPx()
        fun cell(point: Offset): Pair<Weekday, Int>? {
            if (point.x < gutter || point.x >= size.width) return null
            val row = list.layoutInfo.visibleItemsInfo.firstOrNull { point.y >= it.offset && point.y < it.offset + it.size } ?: return null
            val day = ((point.x - gutter) * 7 / (size.width - gutter)).toInt().coerceIn(0, 6)
            return Weekday.entries[day] to row.index * 30
        }
        var add = true
        var last: Pair<Weekday, Int>? = null
        fun apply(point: Offset) {
            val next = cell(point) ?: return
            if (next != last) paint(next.first, next.second, if (mode == 0) null else week, add)
            last = next
        }
        detectDragGesturesAfterLongPress(onDragStart = { point ->
            last = null
            val first = cell(point)
            add = mode == 1 || (mode == 0 && first != null && currentDays[first.first]?.windows().orEmpty().none {
                val start = it.start.toSecondOfDay() / 60
                start < first.second + 30 && start + it.durationMinutes > first.second
            })
            apply(point)
        }) { change, _ -> change.consume(); apply(change.position) }
    }, state = list) {
        items(48) { slot ->
            val minute = slot * 30
            val time = "%02d:%02d".format(minute / 60, minute % 60)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(time, Modifier.width(44.dp), fontSize = 11.sp)
                Weekday.entries.forEach { day ->
                    fun covered(source: Map<Weekday, DayAvailability>) = source[day]?.windows().orEmpty().any {
                        val start = it.start.toSecondOfDay() / 60
                        start < minute + 30 && start + it.durationMinutes > minute
                    }
                    val selected = covered(days)
                    val color = when {
                        mode != 0 && selected && !covered(base) -> Color(0xFFB8D5F5)
                        mode != 0 && !selected && covered(base) -> Color(0xFFE4D7DD)
                        selected -> Color(0xFFF0BFC4)
                        else -> Color.White
                    }
                    Box(Modifier.weight(1f).height(32.dp).background(color)
                        .border(0.5.dp, Color(0xFFE6E6E9))
                        .selectable(selected, enabled = enabled && (mode == 0 || week != null), role = Role.Checkbox) {
                            paint(day, minute, if (mode == 0) null else week, when (mode) { 1 -> true; 2 -> false; else -> !selected })
                        }.semantics { contentDescription = "${day.label}요일 $time" })
                }
            }
        }
    }
}
