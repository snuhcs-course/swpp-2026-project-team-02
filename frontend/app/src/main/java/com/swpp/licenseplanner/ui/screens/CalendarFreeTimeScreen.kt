package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.R
import com.swpp.licenseplanner.data.AvailabilityWire
import com.swpp.licenseplanner.domain.FreeTime
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.FreeTimePhase
import com.swpp.licenseplanner.ui.FreeTimeSearch
import com.swpp.licenseplanner.ui.RemainingStudyState
import com.swpp.licenseplanner.ui.components.*
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkBody
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

internal fun freeTimeLabel(start: LocalDateTime, end: LocalDateTime): String =
    "${start.monthValue}월 ${start.dayOfMonth}일 (${Weekday.of(start.dayOfWeek).label}) ${WireDateTime.formatTime(start.toLocalTime())}–" +
        (if (start.toLocalDate() != end.toLocalDate()) "다음 날 " else "") + WireDateTime.formatTime(end.toLocalTime())

private fun bandLabel(source: SessionPlan): String {
    val band = AvailabilityWire.context(source.original)?.let(FreeTime::band) ?: return ""
    fun clock(minutes: Int) = WireDateTime.formatTime(LocalTime.of((minutes / 60) % 24, minutes % 60))
    return "${clock(band.startMinute)}–${if (band.endMinute >= 1440) "다음 날 " else ""}${clock(band.endMinute)}"
}

@Composable
private fun FreeTimeInfo(title: String, body: String) {
    MutedBox {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.ic_calendar_clock), contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = Ink)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = InkBody)
            }
        }
    }
}

/** Where sessions can still be moved when a calendar search cannot place them. */
internal fun freeTimeFallback(remaining: Boolean): String =
    if (remaining) REMAINING_STUDY_FALLBACK else "'시간 직접 옮기기'에서 직접 고를 수 있어요."

@Composable
private fun FreeTimeStatus(phase: FreeTimePhase, source: SessionPlan, remaining: Boolean = false) {
    val fallback = freeTimeFallback(remaining)
    when (phase) {
        FreeTimePhase.RequestingPermission, FreeTimePhase.AwaitingPermission, FreeTimePhase.Reading ->
            FreeTimeInfo("캘린더에서 빈 시간을 찾는 중이에요", "평소 공부하는 시간대(${bandLabel(source)})에서 캘린더 일정과 다른 공부 일정을 피한 시간을 찾고 있어요.")
        FreeTimePhase.PermissionDenied -> AlertCard("캘린더를 확인하지 못했어요", "캘린더 권한이 없어 빈 시간을 찾지 못했어요. $fallback")
        FreeTimePhase.Unavailable -> AlertCard("캘린더를 읽지 못했어요", "캘린더를 읽을 수 없어 빈 시간을 확인하지 못했어요. $fallback")
        FreeTimePhase.NoTime -> AlertCard(if (remaining) "남은 공부를 모두 배치할 빈 시간이 없어요" else "시험 전까지 빈 시간이 없어요",
            "평소 공부하는 시간대(${bandLabel(source)})에 캘린더가 비어 있는 시간이 없어요. $fallback")
        FreeTimePhase.Results -> Unit
    }
}

@Composable
fun CalendarFreeTimeScreen(search: FreeTimeSearch, onSelect: (Int) -> Unit, onConfirm: () -> Unit,
                           onManual: () -> Unit, onRetry: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val results = search.phase == FreeTimePhase.Results
    AppScreen("일정 조정", onNav = onBack, footer = {
        if (results) PrimaryButton("이번 한 번만 옮기기", onConfirm, enabled = search.selected != null)
        else {
            if (search.phase in setOf(FreeTimePhase.PermissionDenied, FreeTimePhase.Unavailable, FreeTimePhase.NoTime)) {
                if (search.phase == FreeTimePhase.Unavailable) SecondaryButton("다시 찾기", onRetry)
                PrimaryButton("시간 직접 옮기기", onManual)
            }
            SecondaryButton("돌아가기", onBack)
        }
    }) {
        if (search.source.isDemo) Note("로컬 시연 계산 · 서버나 AI를 사용하지 않아요.")
        Heading("캘린더에서 빈 시간", search.source.current.schedules.single { it.scheduleId == search.targetId }.topic)
        if (results) {
            Note("시험일 전 빈 시간")
            search.suggestions.forEachIndexed { index, suggestion ->
                val label = freeTimeLabel(suggestion.start, suggestion.end)
                val details = "${Duration.between(suggestion.start, suggestion.end).toMinutes()}분 · " +
                    if (suggestion.outsideWindows) "입력한 공부 시간 밖 · 이번 한 번만" else "입력한 공부 시간 안"
                ChoiceCard(search.selected == index, { onSelect(index) }, choiceLabel = "$label · $details") {
                    Column {
                        Text(label, style = MaterialTheme.typography.titleSmall, color = Ink)
                        Note(details)
                    }
                }
            }
            Note("캘린더 일정과 다른 공부 일정을 피했어요. 입력한 공부 시간은 바뀌지 않아요.")
            SecondaryButton("시간 직접 옮기기", onManual)
        } else FreeTimeStatus(search.phase, search.source)
    }
}

@Composable
fun RemainingCalendarFreeTimeScreen(search: RemainingStudyState.CalendarSearch, onConfirm: () -> Unit,
                                    onRetry: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    AppScreen("남은 공부 다시 계획하기", onNav = onBack, footer = {
        when (search.phase) {
            FreeTimePhase.Results -> PrimaryButton("이번 한 번만 이 시간으로 다시 계획하기", onConfirm)
            FreeTimePhase.Unavailable -> PrimaryButton("다시 찾기", onRetry)
            else -> Unit
        }
        SecondaryButton("계획으로 돌아가기", onBack)
    }) {
        if (search.input.source.isDemo) Note("로컬 시연 계산 · 서버나 AI를 사용하지 않아요.")
        Heading("캘린더 빈 시간으로 다시 계획")
        if (search.phase == FreeTimePhase.Results) {
            Heading("남은 시간 ${search.input.work.totalMinutes}분 · 일정 ${search.placements.size}개")
            if (search.changed) FreeTimeInfo("캘린더가 바뀌어 시간을 다시 찾았어요", "새 일정과 겹치지 않도록 시간을 다시 배치했어요. 바뀐 시간을 확인하고 다시 눌러 주세요.")
            for (placed in search.placements) {
                MutedBox {
                    Text(placed.row.topic, style = MaterialTheme.typography.titleSmall, color = Ink)
                    Note(freeTimeLabel(requireNotNull(WireDateTime.parseOrNull(placed.row.startDate)), requireNotNull(WireDateTime.parseOrNull(placed.row.endDate))))
                    val minutes = search.input.work.instructions.single { it.scheduleId == placed.sourceId }.remainingMinutes
                    Note("${minutes}분" + if (placed.outsideWindows) " · 입력한 공부 시간 밖 · 이번 한 번만" else " · 입력한 공부 시간 안")
                }
            }
            Note("캘린더 일정과 다른 공부 일정을 피했어요. 입력한 공부 시간은 바뀌지 않고, 이번 한 번만 적용돼요.")
            if (search.input.work.contributions.any { it.report != null && it.remainingMinutes > 0 }) {
                Note("다시 배치하는 일정의 이전 공부 기록은 그대로 저장되지만 현재 계획에서는 표시되지 않아요. 새 일정은 기록 없음으로 시작해요.")
            }
        } else FreeTimeStatus(search.phase, search.input.source, remaining = true)
    }
}
