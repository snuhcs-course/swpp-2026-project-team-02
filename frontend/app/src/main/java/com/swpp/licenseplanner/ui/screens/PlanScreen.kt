package com.swpp.licenseplanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.R
import com.swpp.licenseplanner.data.model.StudyPlanResponse
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.DayPlan
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.PlanSession
import com.swpp.licenseplanner.domain.WeekPlan
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.components.DashedBox
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.IconButton44
import com.swpp.licenseplanner.ui.components.MutedBox
import com.swpp.licenseplanner.ui.components.NavIcon
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.OutlinedCard
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.components.Tag
import com.swpp.licenseplanner.ui.components.TopBar
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkBody
import com.swpp.licenseplanner.ui.theme.InkSecondary
import com.swpp.licenseplanner.ui.theme.MonoStyles
import com.swpp.licenseplanner.ui.theme.Paper
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * VIEW-01–VIEW-04, adapted from frame 2007:253: one Monday-based week at a time with
 * bounded previous/next, seven day sections, returned session cards, the AI-authored
 * summary when present, and the demo statement for local plans. The phase timeline,
 * on-track status and calendar export from the frame are omitted (no backing data).
 */
@Composable
fun PlanScreen(response: StudyPlanResponse, isDemo: Boolean, onEdit: () -> Unit) {
    val weeks = remember(response) { PlanGrouping.weeks(response.schedules) }
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
            // Written-only scope (VIEW-03, design D14), shown for nonempty and empty plans alike.
            item { Heading(CERTIFICATION_NAME, "필기 시험 준비 계획") }
            if (isDemo) {
                item {
                    MutedBox {
                        Text("로컬 시연 계산", style = MaterialTheme.typography.titleSmall, color = Ink)
                        Note("데모 플래너가 기기 안에서 만든 계획이에요. 백엔드나 AI 결과가 아니에요.")
                    }
                }
            }
            if (summary != null) item { AiSummary(summary) }
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
                items(week.days.size, key = { week.days[it].date.toEpochDay() }) { DaySection(week.days[it]) }
            }
            item { SecondaryButton("입력 수정하기", onEdit) }
        }
    }
}

@Composable
private fun AiSummary(summary: String) {
    DashedBox {
        Tag("AI", mono = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("AI가 작성한 설명", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = Ink)
        }
    }
}

@Composable
private fun WeekNavigator(week: WeekPlan, index: Int, count: Int, onPrevious: () -> Unit, onNext: () -> Unit) {
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

private fun monthDay(date: LocalDate) = "${date.monthValue}월 ${date.dayOfMonth}일"

private fun dayLabel(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

@Composable
private fun DaySection(day: DayPlan) {
    if (day.sessions.isEmpty()) {
        MutedBox {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DayStamp(day.date)
                Text("일정 없음", style = MaterialTheme.typography.bodyMedium, color = InkSecondary, modifier = Modifier.weight(1f))
            }
        }
        return
    }
    OutlinedCard {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DayStamp(day.date)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (session in day.sessions) SessionRow(session)
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
private fun SessionRow(session: PlanSession) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Topic exactly as returned; long topics wrap.
            Text(session.topic, style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.weight(1f))
            Text(PlanGrouping.formatDuration(session.durationMinutes), style = MaterialTheme.typography.bodySmall, color = Ink)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${WireDateTime.formatTime(session.start.toLocalTime())}–${WireDateTime.formatTime(session.end.toLocalTime())}",
                style = MonoStyles.number,
                color = InkBody,
            )
            if (session.isExternal) Tag("기존 일정")
        }
    }
}
