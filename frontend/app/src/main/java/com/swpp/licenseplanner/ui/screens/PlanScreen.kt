package com.swpp.licenseplanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.R
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.data.model.StudyPlanRequest
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.DayPlan
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.PlanSession
import com.swpp.licenseplanner.domain.WeekPlan
import com.swpp.licenseplanner.domain.WeeklyTimeUsage
import com.swpp.licenseplanner.domain.WeeklyTimeUsageCalculator
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.ExportBlock
import com.swpp.licenseplanner.ui.RemovedStudySchedule
import com.swpp.licenseplanner.ui.components.ChoiceCard
import com.swpp.licenseplanner.ui.components.DashedBox
import com.swpp.licenseplanner.ui.components.EXPORT_SUBTITLE
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.IconButton44
import com.swpp.licenseplanner.ui.components.MutedBox
import com.swpp.licenseplanner.ui.components.NavIcon
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.OutlinedCard
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.components.Tag
import com.swpp.licenseplanner.ui.components.TopBar
import com.swpp.licenseplanner.ui.components.exportBlockText
import com.swpp.licenseplanner.ui.components.exportButtonText
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkBody
import com.swpp.licenseplanner.ui.theme.InkSecondary
import com.swpp.licenseplanner.ui.theme.Outline
import com.swpp.licenseplanner.ui.theme.MutedSurface
import com.swpp.licenseplanner.ui.theme.MonoStyles
import com.swpp.licenseplanner.ui.theme.Paper
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Adapted from frame 2007:253: one Monday-based week at a time with
 * bounded previous/next, seven day sections, returned session cards, the AI-authored
 * summary when present, and the demo statement for local plans. The phase timeline and
 * on-track status from the frame are omitted (no backing data). The calendar export action
 * (2007:352) copies [exportCount] study sessions to a chosen device calendar; [exportBlock]
 * disables it with the reason. [onHome] and [onAdjust] lead to the home and plan-list screens.
 */
@Composable
fun PlanScreen(
    originalRequest: StudyPlanRequest,
    response: StudyPlanResponse,
    isDemo: Boolean,
    exportCount: Int,
    exportBlock: ExportBlock?,
    onExport: () -> Unit,
    onEdit: () -> Unit,
    onHome: () -> Unit,
    onAdjust: () -> Unit,
) {
    val weeks = remember(response) { PlanGrouping.weeks(response.schedules) }
    val weeklyTimeUsage = remember(originalRequest, response) {
        WeeklyTimeUsageCalculator.calculate(originalRequest, response.schedules)
    }
    var weekIndex by rememberSaveable { mutableIntStateOf(0) }
    val week = weeks.getOrNull(weekIndex.coerceIn(0, (weeks.size - 1).coerceAtLeast(0)))
    val summary = response.agentSummary?.takeIf { it.isNotBlank() }

    Scaffold(
        containerColor = Paper,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopBar("내 계획", NavIcon.Back, onEdit) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Written-only scope, shown for nonempty and empty plans alike.
            item { Heading(CERTIFICATION_NAME, "필기 시험 준비 계획") }
            if (isDemo) {
                item {
                    MutedBox {
                        Text("로컬 시연 계산", style = MaterialTheme.typography.titleSmall, color = Ink)
                        Note("데모 플래너가 기기 안에서 만든 계획이에요. 백엔드나 AI 결과가 아니에요.")
                    }
                }
            }
            if (week == null) {
                item {
                    OutlinedCard {
                        Text("돌려받은 공부 일정이 없어요", style = MaterialTheme.typography.titleSmall, color = Ink)
                        Note("시험 날짜나 공부 시간을 바꿔 다시 만들어 보세요.")
                    }
                }
            } else {
                item {
                    WeekNavigator(
                        week = week,
                        index = weekIndex,
                        count = weeks.size,
                        onPrevious = { weekIndex-- },
                        onNext = { weekIndex++ },
                    )
                }
                val selectedWeekUsage = weeklyTimeUsage.firstOrNull { it.monday == week.monday }
                if (selectedWeekUsage != null) item { WeeklyTimeUsageChart(selectedWeekUsage) }
                if (summary != null) item { PlanSummary(summary) }
                items(week.days.size, key = { week.days[it].date.toEpochDay() }) { DaySection(week.days[it]) }
            }
            item {
                ContextHelpButton(ContextHelpTopic.CALENDAR_EXPORT)
            }
            item {
                PrimaryButton(
                    exportButtonText(exportCount),
                    onExport,
                    enabled = exportBlock == null,
                    subtitle = exportBlock?.let(::exportBlockText) ?: EXPORT_SUBTITLE,
                )
            }
            item { SecondaryButton("계획 조정하기", onAdjust) }
            item { SecondaryButton("입력 수정하기", onEdit) }
            item { SecondaryButton("처음으로", onHome) }
        }
    }
}

@Composable
internal fun WeeklyTimeUsageChart(week: WeeklyTimeUsage) {
    val availableDays = week.days.filter { it.availableMinutes > 0 }
    if (availableDays.isEmpty()) return
    val scale = availableDays.maxOfOrNull { maxOf(it.availableMinutes, it.plannedMinutes) }
        ?.coerceAtLeast(1L)?.toFloat() ?: 1f
    MutedBox {
        Text("일별 공부 시간", style = MaterialTheme.typography.titleSmall, color = Ink)
        Note("이 주의 요일별 가용 시간 중 계획에 배정된 시간을 비교해요.")
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            availableDays.forEach { day ->
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)} ${monthDay(day.date)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Ink,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "배정 ${PlanGrouping.formatDuration(day.plannedMinutes)} / 가용 ${PlanGrouping.formatDuration(day.availableMinutes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkBody,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(12.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MutedSurface),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((day.availableMinutes / scale).coerceIn(0f, 1f))
                                .height(12.dp)
                                .background(Outline),
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth((day.plannedMinutes / scale).coerceIn(0f, 1f))
                                .height(12.dp)
                                .background(Ink),
                        )
                    }
                }
            }
        }
        Note("회색은 가용 시간, 검정은 배정 시간이에요. 가용 시간을 모두 사용하지 않고 일부를 여유 시간으로 남길 수 있어요. 화살표를 눌러 주차별로 확인할 수 있어요.")
    }
}

@Composable
internal fun PlanSummary(summary: String) {
    MutedBox {
        Text("계획 요약", style = MaterialTheme.typography.titleSmall, color = Ink)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = Ink)
    }
}

@Composable
internal fun WeekNavigator(week: WeekPlan, index: Int, count: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton44(R.drawable.ic_back, "이전 주", onPrevious, enabled = index > 0)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(weekRange(week), style = MaterialTheme.typography.labelMedium, color = InkBody, textAlign = TextAlign.Center)
            Text("${index + 1} / ${count}주", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        }
        // The back arrow mirrored serves as the next arrow.
        Row(Modifier.scale(scaleX = -1f, scaleY = 1f)) {
            IconButton44(R.drawable.ic_back, "다음 주", onNext, enabled = index < count - 1)
        }
    }
}

private fun weekRange(week: WeekPlan): String {
    val end = week.sunday
    val endText = if (end.month == week.monday.month) "${end.dayOfMonth}일" else monthDay(end)
    return "${monthDay(week.monday)} – $endText"
}

internal fun monthDay(date: LocalDate) = "${date.monthValue}월 ${date.dayOfMonth}일"

private fun dayLabel(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

/**
 * One day of the plan. With [onSelect], study sessions become single-choice options with
 * [selectedId] marked; existing schedules stay plain rows. The study session [movedId] gets a
 * “옮긴 일정” tag.
 */
@Composable
internal fun DaySection(
    day: DayPlan,
    selectedId: String? = null,
    onSelect: ((String) -> Unit)? = null,
    selectionEnabled: Boolean = true,
    movedId: String? = null,
    availabilityLabel: String? = null,
    dateMarkers: List<String> = emptyList(),
    removedSchedules: List<RemovedStudySchedule> = emptyList(),
    highlightedDestination: PlanSession? = null,
    onNavigateToDestination: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (day.sessions.isEmpty()) {
        MutedBox(modifier = modifier) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DayStamp(day.date)
                Text("일정 없음", style = MaterialTheme.typography.bodyMedium, color = InkSecondary, modifier = Modifier.weight(1f))
            }
            DayConstraintMarkers(availabilityLabel, dateMarkers)
        }
        return
    }
    OutlinedCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DayStamp(day.date)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (session in day.sessions) {
                        val removedSchedule = removedSchedules.firstOrNull { it.original == session }
                        val highlighted = session == highlightedDestination
                        val moved = !session.isExternal && session.scheduleId == movedId
                        if (removedSchedule != null) SessionRow(
                            session,
                            removed = true,
                            removedDestination = removedSchedule.destination,
                            onNavigate = removedSchedule.destination?.let { { onNavigateToDestination(it.scheduleId) } },
                        )
                        else if (onSelect == null || session.isExternal) SessionRow(session, moved, highlighted = highlighted)
                        else SelectableSession(session, day.date, session.scheduleId == selectedId, selectionEnabled, moved, highlighted) { onSelect(session.scheduleId) }
                    }
                }
            }
            DayConstraintMarkers(availabilityLabel, dateMarkers)
        }
    }
}

@Composable
private fun DayConstraintMarkers(availabilityLabel: String?, dateMarkers: List<String>) {
    if (availabilityLabel == null && dateMarkers.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (availabilityLabel != null) Tag(availabilityLabel)
        if (dateMarkers.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                dateMarkers.forEach { Tag(it) }
            }
        }
    }
}

@Composable
private fun DayStamp(date: LocalDate) {
    Column(Modifier.width(58.dp)) {
        Text(dayLabel(date), style = MaterialTheme.typography.titleSmall, color = Ink)
        Text(monthDay(date), style = MaterialTheme.typography.labelSmall, color = InkSecondary)
    }
}

@Composable
private fun SelectableSession(
    session: PlanSession,
    date: LocalDate,
    selected: Boolean,
    enabled: Boolean,
    moved: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    val label = "${monthDay(date)} ${dayLabel(date)} ${sessionTime(session)} ${session.topic}" +
        (if (moved) " $MOVED_TAG" else "") + (if (enabled) "" else " $SELECTION_LOCKED")
    ChoiceCard(selected = selected, onClick = onClick, choiceLabel = label, enabled = enabled) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SessionRow(session, moved, highlighted = highlighted)
            if (!enabled) Text(SELECTION_LOCKED, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        }
    }
}

private fun sessionTime(session: PlanSession) =
    "${WireDateTime.formatTime(session.start.toLocalTime())}–${WireDateTime.formatTime(session.end.toLocalTime())}"

@Composable
private fun SessionRow(
    session: PlanSession,
    moved: Boolean = false,
    removed: Boolean = false,
    removedDestination: PlanSession? = null,
    highlighted: Boolean = false,
    onNavigate: (() -> Unit)? = null,
) {
    val highlightColor by animateColorAsState(
        targetValue = if (highlighted) MutedSurface else Color.Transparent,
        animationSpec = tween(durationMillis = 450),
        label = "schedule-destination-highlight",
    )
    Column(
        modifier = Modifier
            .background(highlightColor, MaterialTheme.shapes.small)
            .padding(if (highlighted) 6.dp else 0.dp)
            .then(
                if (removed && removedDestination != null && onNavigate != null) {
                    Modifier.clickable(role = Role.Button, onClickLabel = "조정된 일정으로 이동", onClick = onNavigate)
                } else Modifier,
            ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Topic exactly as returned; long topics wrap.
            Text(
                session.topic,
                style = MaterialTheme.typography.titleSmall.copy(textDecoration = if (removed) TextDecoration.LineThrough else TextDecoration.None),
                color = if (removed) InkSecondary else Ink,
                modifier = Modifier.weight(1f),
            )
            Text(PlanGrouping.formatDuration(session.durationMinutes), style = MaterialTheme.typography.bodySmall, color = if (removed) InkSecondary else Ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(sessionTime(session), style = MonoStyles.number, color = if (removed) InkSecondary else InkBody)
            if (session.isExternal) Tag("기존 일정")
            if (moved) Tag(MOVED_TAG)
            if (removed) Tag("조정 전 위치 · 임시")
        }
        if (removed) {
            if (removedDestination != null && onNavigate != null) {
                Text(
                    "이동 위치: ${dayLabel(removedDestination.start.toLocalDate())} ${monthDay(removedDestination.start.toLocalDate())} ${sessionTime(removedDestination)} · 눌러서 이동",
                    style = MaterialTheme.typography.bodySmall,
                    color = Ink,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else Note("새 일정 위치를 찾지 못했어요.")
        }
    }
}

private const val MOVED_TAG = "옮긴 일정"
private const val SELECTION_LOCKED = "선택 변경 불가"
