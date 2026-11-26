package com.swpp.licenseplanner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.sp
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.PlanSession
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.StudyCheckInStatus
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.PlanStorage
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.LoadingBlock
import com.swpp.licenseplanner.ui.components.PrimaryButton
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
    var createMenuOpen by rememberSaveable { mutableStateOf(false) }
    fun changeMonth(target: YearMonth) {
        monthKey = target.toString()
        selectedDay = target.atDay(selectedDate.dayOfMonth.coerceAtMost(target.lengthOfMonth())).toEpochDay()
    }
    Scaffold(
        containerColor = Color.White,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            Row(
                Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                CalendarNavButton("☰", "계획 목록", onAdjust)
                Text("${month.monthValue}월", Modifier.clickable {
                    monthKey = YearMonth.from(today).toString()
                    selectedDay = today.toEpochDay()
                }, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text("${month.year}", Modifier.weight(1f).padding(start = 4.dp), fontSize = 12.sp, color = CalendarMuted)
                CalendarNavButton("‹", "이전 달") { changeMonth(month.minusMonths(1)) }
                CalendarNavButton("›", "다음 달") { changeMonth(month.plusMonths(1)) }
                ContextHelpButton(ContextHelpTopic.START)
            }
        },
        bottomBar = {
            Box(Modifier.navigationBarsPadding()) {
                CalendarAgenda(selectedDate, byDate[selectedDate].orEmpty(), plans.size, storage, onOpenPlan)
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 34.dp)) {
                    Surface(
                        onClick = { createMenuOpen = true },
                        shape = CircleShape,
                        color = Color(0xFFF1F2F4),
                        modifier = Modifier.size(58.dp).semantics { contentDescription = "계획 또는 일정 생성" },
                    ) { Box(contentAlignment = Alignment.Center) { Text("+", fontSize = 32.sp) } }
                    DropdownMenu(expanded = createMenuOpen, onDismissRequest = { createMenuOpen = false }) {
                        DropdownMenuItem(text = { Text("학습 계획 생성") }, enabled = storage == PlanStorage.Ready, onClick = {
                            createMenuOpen = false
                            onCreate()
                        })
                        if (hasDraft) DropdownMenuItem(text = { Text("이어서 만들기") }, enabled = storage == PlanStorage.Ready, onClick = {
                            createMenuOpen = false
                            onResume()
                        })
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            conflictNotices()
            if (storage == PlanStorage.Loading) LoadingBlock(LOADING_SAVED_PLANS)
            if (storage == PlanStorage.LoadFailure || storage == PlanStorage.SaveFailure) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StorageAlert(storage, onRetryLoad, onRetrySave)
                }
            }
            CalendarMonth(month, selectedDate, today, byDate, plans, Modifier.weight(1f)) { date ->
                selectedDay = date.toEpochDay()
                monthKey = YearMonth.from(date).toString()
            }
        }
    }
}

private val CalendarGreen = Color(0xFF48A66C)
private val CalendarYellow = Color(0xFFF2CE55)
private val CalendarRed = Color(0xFFD85A61)
private val CalendarGray = Color(0xFF55585F)
private val CalendarMuted = Color(0xFF777E79)
private val CalendarToday = Color(0xFF2B8A57)

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
    Surface(onClick = onClick, shape = MaterialTheme.shapes.small, color = Color.White, modifier = Modifier.size(36.dp, 44.dp).semantics { contentDescription = label }) {
        Box(contentAlignment = Alignment.Center) { Text(symbol, fontSize = 22.sp, color = Color(0xFF34373A)) }
    }
}

@Composable
private fun CalendarMonth(
    month: YearMonth,
    selected: LocalDate,
    today: LocalDate,
    entries: Map<LocalDate, List<CalendarEntry>>,
    plans: List<SessionPlan>,
    modifier: Modifier = Modifier,
    onSelect: (LocalDate) -> Unit,
) {
    val examDates = remember(plans) { plans.mapNotNull { plan ->
        WireDateTime.parseOrNull(plan.original.examDate)?.toLocalDate()?.let { it to plan.ordinal }
    }.groupBy({ it.first }, { it.second }) }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(35.dp).padding(horizontal = 10.dp)) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEach { day ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Text(day, fontSize = 12.sp, color = CalendarMuted)
                }
            }
        }
        calendarDays(month).chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 10.dp)) {
                week.forEach { date ->
                    val dayEntries = entries[date].orEmpty()
                    val inMonth = YearMonth.from(date) == month
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clickable { onSelect(date) }
                            .padding(horizontal = 2.dp)
                            .semantics { contentDescription = "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일, 일정 ${dayEntries.size}개" },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            Modifier.padding(top = 3.dp, bottom = 6.dp).size(32.dp, 34.dp)
                                .background(if (date == today) CalendarToday else Color.Transparent, CircleShape)
                                .then(if (date == selected && date != today) Modifier.background(Color(0xFFF1F2F4), CircleShape) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(date.dayOfMonth.toString(), fontSize = 17.sp,
                                color = when { date == today -> Color.White; !inMonth -> Color(0xFFC6C7C9); else -> Color(0xFF202124) })
                        }
                        if (examDates[date].orEmpty().isNotEmpty()) {
                            Text("시험일", Modifier.fillMaxWidth().background(Color(0xFFFFE7E9), RoundedCornerShape(4.dp)).padding(vertical = 1.dp),
                                fontSize = 9.sp, color = Color(0xFFB52E3C), maxLines = 1)
                        }
                        dayEntries.take(3).forEach { entry ->
                            Text(
                                entry.session.topic.ifBlank { if (entry.session.isExternal) "기존 일정" else "학습 일정" },
                                Modifier.fillMaxWidth().padding(bottom = 3.dp)
                                    .background(entryColor(entry), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                                color = if (entry.status == StudyCheckInStatus.Partly || entry.session.isExternal) Color(0xFF26262A) else Color.White,
                                fontSize = 9.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (dayEntries.size > 3) Text("+${dayEntries.size - 3}개 더", fontSize = 8.sp, color = CalendarMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarAgenda(date: LocalDate, entries: List<CalendarEntry>, planCount: Int, storage: PlanStorage, onOpenPlan: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().height(153.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFEEEEEE)))
        Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 27.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Column(Modifier.width(128.dp)) {
                Text("${date.dayOfMonth}일 ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${date.year}년 ${date.monthValue}월", Modifier.padding(top = 10.dp), fontSize = 11.sp, color = CalendarMuted)
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(end = 42.dp)) {
                if (entries.isEmpty()) {
                    Text(if (planCount == 0 && storage == PlanStorage.Ready) "계획을 만들어 시작하세요" else "일정이 없습니다",
                        Modifier.padding(top = 15.dp), fontSize = 13.sp, color = Color(0xFF999999))
                } else entries.forEach { entry ->
                    Row(Modifier.fillMaxWidth().clickable { onOpenPlan(entry.planOrdinal) }.padding(bottom = 11.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Box(Modifier.width(3.dp).height(36.dp).background(entryColor(entry), RoundedCornerShape(2.dp)))
                        Column {
                            Text(entry.session.topic.ifBlank { if (entry.session.isExternal) "기존 일정" else "학습 일정" },
                                fontSize = 11.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${entry.session.start.toLocalTime()} – ${entry.session.end.toLocalTime()}", fontSize = 10.sp, color = CalendarMuted)
                        }
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
