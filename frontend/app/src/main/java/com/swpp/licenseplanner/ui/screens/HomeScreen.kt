package com.swpp.licenseplanner.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.PlanSession
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.StudyCheckInStatus
import com.swpp.licenseplanner.ui.PlanStorage
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.LoadingBlock
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Calendar-first home adapted from calendar-prototype. Every displayed session comes from the
 * current plan snapshot; the prototype's example plans and in-memory actions are not used.
 */
@Composable
fun HomeScreen(
    plans: List<SessionPlan>,
    savedPlans: List<SessionPlan>,
    storage: PlanStorage,
    hasDraft: Boolean,
    onResume: () -> Unit,
    onCreate: () -> Unit,
    onAdjust: () -> Unit,
    onOpenPlan: (Int) -> Unit,
    onRetryLoad: () -> Unit,
    onRetrySave: () -> Unit,
    conflictNotices: @Composable () -> Unit = {},
) {
    val today = LocalDate.now()
    var monthKey by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    val month = YearMonth.parse(monthKey)
    val selectedDate = LocalDate.ofEpochDay(selectedDay)
    val entries = remember(plans) { calendarEntries(plans) }
    val byDate = remember(entries) { entries.groupBy { it.session.start.toLocalDate() } }
    AppScreen(
        title = "학습 달력",
        navIcon = null,
        trailing = { ContextHelpButton(ContextHelpTopic.START) },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("${month.monthValue}월", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text("${month.year}", style = MaterialTheme.typography.bodyMedium, color = CalendarMuted)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CalendarNavButton("‹", "이전 달") {
                    val target = month.minusMonths(1)
                    monthKey = target.toString()
                    selectedDay = target.atDay(selectedDate.dayOfMonth.coerceAtMost(target.lengthOfMonth())).toEpochDay()
                }
                CalendarNavButton("›", "다음 달") {
                    val target = month.plusMonths(1)
                    monthKey = target.toString()
                    selectedDay = target.atDay(selectedDate.dayOfMonth.coerceAtMost(target.lengthOfMonth())).toEpochDay()
                }
            }
        }
        CalendarMonth(month, selectedDate, today, byDate) { date ->
            selectedDay = date.toEpochDay()
            monthKey = YearMonth.from(date).toString()
        }
        CalendarAgenda(selectedDate, byDate[selectedDate].orEmpty(), onOpenPlan)
        conflictNotices()
        if (storage == PlanStorage.Loading) LoadingBlock(LOADING_SAVED_PLANS)
        StorageAlert(storage, onRetryLoad, onRetrySave)
        planCountNote(storage, plans.size, savedPlans.size)?.let { Note(it) }
        PrimaryButton("계획 생성", onCreate, subtitle = "$CERTIFICATION_NAME 필기", enabled = storage == PlanStorage.Ready)
        if (hasDraft) SecondaryButton("이어서 만들기", onResume, enabled = storage == PlanStorage.Ready)
        SecondaryButton("내 계획 목록", onAdjust)
    }
}

private val CalendarGreen = Color(0xFF48A66C)
private val CalendarYellow = Color(0xFFF2CE55)
private val CalendarRed = Color(0xFFD85A61)
private val CalendarGray = Color(0xFF55585F)
private val CalendarMuted = Color(0xFF777E79)
private val CalendarBorder = Color(0xFFE4EAE6)

internal data class CalendarEntry(val planOrdinal: Int, val session: PlanSession, val status: StudyCheckInStatus?)

internal fun calendarEntries(plans: List<SessionPlan>): List<CalendarEntry> = plans.flatMap { plan ->
    PlanGrouping.sessions(plan.current.schedules).map { session ->
        CalendarEntry(plan.ordinal, session, plan.checkInFor(session.scheduleId)?.status)
    }
}.sortedWith(compareBy<CalendarEntry> { it.session.start }.thenBy { it.planOrdinal })

internal fun calendarDays(month: YearMonth): List<LocalDate> {
    val first = month.atDay(1)
    val leading = first.dayOfWeek.value % 7
    val count = ((leading + month.lengthOfMonth() + 6) / 7) * 7
    return (0 until count).map { first.plusDays((it - leading).toLong()) }
}

private fun entryColor(entry: CalendarEntry): Color = when {
    entry.session.isExternal -> CalendarMuted
    entry.status == StudyCheckInStatus.Done -> CalendarGreen
    entry.status == StudyCheckInStatus.Partly -> CalendarYellow
    entry.status == StudyCheckInStatus.Missed -> CalendarRed
    else -> CalendarGray
}

@Composable
private fun CalendarNavButton(symbol: String, label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.small, color = Color(0xFFF4F6F4), modifier = Modifier.size(44.dp).semantics { contentDescription = label }) {
        Box(contentAlignment = Alignment.Center) { Text(symbol, style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun CalendarMonth(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    entries: Map<LocalDate, List<CalendarEntry>>,
    onSelect: (LocalDate) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { day ->
                Box(Modifier.weight(1f).heightIn(min = 28.dp), contentAlignment = Alignment.Center) {
                    Text(day, style = MaterialTheme.typography.labelSmall, color = CalendarMuted)
                }
            }
        }
        calendarDays(month).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                week.forEach { date ->
                    val dayEntries = entries[date].orEmpty()
                    val inMonth = YearMonth.from(date) == month
                    Surface(
                        onClick = { onSelect(date) },
                        shape = MaterialTheme.shapes.small,
                        color = if (date == selected) Color(0xFFEDF5F0) else Color.White,
                        border = BorderStroke(1.dp, if (date == today) CalendarGreen else CalendarBorder),
                        modifier = Modifier.weight(1f).heightIn(min = 49.dp).semantics {
                            contentDescription = "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일, 일정 ${dayEntries.size}개"
                        },
                    ) {
                        Column(Modifier.padding(top = 4.dp, bottom = 3.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.labelMedium, color = if (inMonth) Color(0xFF202124) else CalendarMuted)
                            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                dayEntries.take(3).forEach { entry -> Box(Modifier.size(5.dp).background(entryColor(entry), CircleShape)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarAgenda(date: LocalDate, entries: List<CalendarEntry>, onOpenPlan: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${date.dayOfMonth}일 ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (entries.isEmpty()) {
            Text("일정이 없습니다", style = MaterialTheme.typography.bodyMedium, color = CalendarMuted)
        } else entries.forEach { entry ->
            Surface(onClick = { onOpenPlan(entry.planOrdinal) }, shape = MaterialTheme.shapes.medium, color = Color(0xFFFAFCFB), border = BorderStroke(1.dp, CalendarBorder), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(4.dp, 38.dp).background(entryColor(entry)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(entry.session.topic.ifBlank { if (entry.session.isExternal) "기존 일정" else "학습 일정" }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${entry.session.start.toLocalTime()} – ${entry.session.end.toLocalTime()} · 계획 ${entry.planOrdinal}", style = MaterialTheme.typography.bodySmall, color = CalendarMuted)
                    }
                }
            }
        }
    }
}

/**
 * The plan count under the home actions. Only a saved collection is called saved; before the
 * saved plans are read nothing is claimed, and after a failed save the shown and saved counts differ.
 */
internal fun planCountNote(storage: PlanStorage, shown: Int, saved: Int): String? = when (storage) {
    PlanStorage.Loading, PlanStorage.LoadFailure -> null
    PlanStorage.Saving -> "계획 ${shown}개를 기기에 저장하는 중이에요."
    PlanStorage.SaveFailure ->
        if (shown == saved) "계획 ${shown}개의 마지막 변경을 기기에 저장하지 못했어요."
        else "지금 화면에는 계획 ${shown}개가 있지만, 기기에 저장된 계획은 ${saved}개예요."
    PlanStorage.Ready -> if (shown == 0) "아직 만든 계획이 없어요." else "저장된 계획 ${shown}개가 있어요."
}

/** Ordinals whose shown entry is not the saved one, e.g. a new plan or result whose save failed. */
internal fun unsavedOrdinals(plans: List<SessionPlan>, savedPlans: List<SessionPlan>): Set<Int> {
    val saved = savedPlans.associateBy { it.ordinal }
    return plans.filter { saved[it.ordinal] != it }.mapTo(mutableSetOf()) { it.ordinal }
}

/**
 * A failed read or save of the device's plans, with its retry. Both retries use local storage
 * only; nothing is sent to the server.
 */
@Composable
internal fun StorageAlert(storage: PlanStorage, onRetryLoad: () -> Unit, onRetrySave: () -> Unit) {
    when (storage) {
        PlanStorage.LoadFailure -> {
            AlertCard(
                "저장된 계획을 불러오지 못했어요",
                "기기에 저장된 계획은 지우거나 바꾸지 않았어요. 불러오기 전에는 계획을 만들거나 조정할 수 없어요.",
            )
            PrimaryButton("다시 불러오기", onRetryLoad)
        }
        PlanStorage.SaveFailure -> {
            AlertCard(
                "기기에 계획을 저장하지 못했어요",
                "화면에 보이는 마지막 변경은 아직 저장되지 않아 앱을 닫으면 사라질 수 있어요. " +
                    "저장하기 전에는 계획을 만들거나 조정할 수 없어요. 다시 저장해도 서버에 요청을 다시 보내지 않아요.",
            )
            PrimaryButton("다시 저장하기", onRetrySave)
        }
        else -> Unit
    }
}

internal const val LOADING_SAVED_PLANS = "저장된 계획을 불러오는 중이에요"
