package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.swpp.licenseplanner.R
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.DayPlan
import com.swpp.licenseplanner.domain.PlanGrouping
import com.swpp.licenseplanner.domain.PlanRecovery
import com.swpp.licenseplanner.domain.PlanSession
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.TopicTotal
import com.swpp.licenseplanner.domain.WeekPlan
import com.swpp.licenseplanner.domain.WeeklyTimeUsage
import com.swpp.licenseplanner.domain.WeeklyTimeUsageCalculator
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.ExportBlock
import com.swpp.licenseplanner.ui.PlanDeletionStatus
import com.swpp.licenseplanner.ui.PlanStorage
import com.swpp.licenseplanner.ui.RemovedStudySchedule
import com.swpp.licenseplanner.ui.components.AlertCard
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
import com.swpp.licenseplanner.ui.theme.Divider
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkBody
import com.swpp.licenseplanner.ui.theme.InkMuted
import com.swpp.licenseplanner.ui.theme.InkSecondary
import com.swpp.licenseplanner.ui.theme.Outline
import com.swpp.licenseplanner.ui.theme.MutedSurface
import com.swpp.licenseplanner.ui.theme.MonoStyles
import com.swpp.licenseplanner.ui.theme.Paper
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** The alert a plan view shows for a plan that cannot be adjusted as it stands, from its stored state. */
internal enum class PlanViewAlert(val title: String, val body: String) {
    ReloadRequired(
        "최신 계획을 확인해 주세요",
        "마지막 조정 결과를 확인하지 못해 화면의 일정이 이전 계획일 수 있어요. '일정 조정하기'에서 최신 계획을 불러와 주세요.",
    ),
    Missing("이 계획은 더 이상 조정할 수 없어요", "서버에서 이 계획을 찾을 수 없어요. 일정은 볼 수 있어요."),
    NoIdentity(
        "이 계획은 조정할 수 없어요",
        "서버가 계획 파일 정보를 보내지 않았어요. 일정은 볼 수 있지만, 조정하려면 새 계획을 만들어 주세요.",
    ),
}

/**
 * The stored recovery state takes precedence over a missing identity, as on the adjustment
 * screen; a ready plan with an identity shows no alert.
 */
internal fun planViewAlert(plan: SessionPlan): PlanViewAlert? = when {
    plan.recovery == PlanRecovery.Missing -> PlanViewAlert.Missing
    plan.recovery == PlanRecovery.ReloadRequired -> PlanViewAlert.ReloadRequired
    !plan.hasIdentity -> PlanViewAlert.NoIdentity
    else -> null
}

/** The plan view's creation action, if any. */
internal enum class PlanInputAction(val label: String) {
    /** Opens 공부 시간 with the inputs that produced this plan; generating adds a new plan. */
    EditInputs("입력 바꿔 새 계획 만들기"),
    /** Starts creation as Home does; offered only for a plan without study sessions. */
    NewPlan("새 계획 만들기"),
}

/**
 * The input action is offered only while the creation form still holds the inputs of plan
 * [ordinal] ([generatedPlan] is cleared by any later form change). An empty plan without held
 * inputs offers a new creation instead; other plans offer neither.
 */
internal fun planInputAction(ordinal: Int, generatedPlan: Int?, hasSessions: Boolean): PlanInputAction? = when {
    generatedPlan == ordinal -> PlanInputAction.EditInputs
    !hasSessions -> PlanInputAction.NewPlan
    else -> null
}

/** Index of the week holding [scheduleId], or -1. */
private fun weekIndexHolding(weeks: List<WeekPlan>, scheduleId: String): Int =
    weeks.indexOfFirst { week -> week.days.any { day -> day.sessions.any { it.scheduleId == scheduleId } } }

/**
 * One plan, however it was opened: adapted from frame 2007:253, one Monday-based week at a time
 * with bounded previous/next and that week's study totals, seven days (empty days as compact
 * rows) with returned session cards, then the plan summary when present, and the demo
 * statement for local plans. The phase timeline and
 * on-track status from the frame are omitted (no backing data). A plan awaiting reload, missing
 * on the server or without an identity shows its alert above the week picker; nothing is sent.
 * Study rows carry a separate report action ([checkIns]); a failed local save shows its retry
 * here. The calendar export action (2007:352) copies [exportCount] study sessions to a chosen
 * device calendar; [exportBlock] disables it with the reason. [onAdjust] opens this plan's
 * adjustment, and [onHome] leads home. [initialScheduleId] shows that session's week once, then
 * [onFocusApplied] lets the host forget it, so a later return keeps the learner's week.
 */
@Composable
fun PlanScreen(
    plan: SessionPlan,
    generatedPlan: Int?,
    exportCount: Int,
    exportBlock: ExportBlock?,
    onExport: () -> Unit,
    onRegenerate: () -> Unit = {},
    regenerationReason: String? = null,
    onBack: () -> Unit,
    onEditInputs: () -> Unit,
    onCreate: () -> Unit,
    onHome: () -> Unit,
    onAdjust: () -> Unit,
    checkIns: CheckInRows,
    storage: PlanStorage,
    onRetrySave: () -> Unit,
    conflictNotices: @Composable () -> Unit = {},
    onRemainingStudy: () -> Unit = {},
    remainingStudyReason: String? = null,
    onReassess: () -> Unit = {},
    reassessmentReason: String? = null,
    initialScheduleId: String? = null,
    onFocusApplied: () -> Unit = {},
    deletion: PlanDeletionStatus? = null,
    deletionBlockReason: String? = null,
    onDelete: () -> Boolean = { false },
) {
    val deleting = deletion == PlanDeletionStatus.Deleting
    val deleteFailure = deletion as? PlanDeletionStatus.Failed
    var confirmingDeletion by rememberSaveable(plan.ordinal) { mutableStateOf(false) }
    BackHandler(enabled = deleting) {}
    if (confirmingDeletion) {
        DeletePlanDialog(plan, canDelete = deletionBlockReason == null,
            onConfirm = { if (onDelete()) confirmingDeletion = false },
            onCancel = { confirmingDeletion = false })
    }
    val response = plan.current
    val weeks = remember(response) { PlanGrouping.weeks(response.schedules) }
    val topicTotals = remember(response) { PlanGrouping.topicTotals(response.schedules) }
    val weeklyTimeUsage = remember(plan.original, response) {
        WeeklyTimeUsageCalculator.calculate(plan.original, response.schedules)
    }
    var weekIndex by rememberSaveable { mutableIntStateOf(0) }
    var appliedFocus by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(initialScheduleId) {
        if (initialScheduleId == null) {
            appliedFocus = null
        } else if (initialScheduleId != appliedFocus) {
            weekIndexHolding(weeks, initialScheduleId).takeIf { it >= 0 }?.let { weekIndex = it }
            appliedFocus = initialScheduleId
            onFocusApplied()
        }
    }
    val week = weeks.getOrNull(weekIndex.coerceIn(0, (weeks.size - 1).coerceAtLeast(0)))
    val summary = response.agentSummary?.takeIf { it.isNotBlank() }
    val alert = planViewAlert(plan)
    val inputAction = planInputAction(plan.ordinal, generatedPlan, hasSessions = weeks.isNotEmpty())

    Scaffold(
        containerColor = Paper,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopBar("내 계획", if (deleting) null else NavIcon.Back, onBack) {
                if (!deleting) ContextHelpButton(ContextHelpTopic.CALENDAR_EXPORT)
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Written-only scope, shown for nonempty and empty plans alike.
            item {
                Heading(
                    "계획 ${plan.ordinal} · $CERTIFICATION_NAME",
                    "필기 시험 준비 · 준비 ${wireDate(plan.original.preparationStart)} – 시험 ${wireDate(plan.original.examDate)}",
                )
            }
            if (plan.isDemo) {
                item {
                    MutedBox {
                        Text("로컬 시연 계산", style = MaterialTheme.typography.titleSmall, color = Ink)
                        Note("데모 플래너가 기기 안에서 만든 계획이에요. 백엔드나 AI 결과가 아니에요.")
                    }
                }
            }
            if (alert != null) item { AlertCard(alert.title, alert.body) }
            if (!deleting) item { conflictNotices() }
            if (storage == PlanStorage.SaveFailure && (deleteFailure == null || deletionBlockReason != null)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StorageAlert(storage, onRetryLoad = {}, onRetrySave = onRetrySave)
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
                        enabled = !deleting,
                    )
                }
                item { WeeklyStudyTotals(week) }
                val selectedWeekUsage = weeklyTimeUsage.firstOrNull { it.monday == week.monday }
                if (selectedWeekUsage != null) item { WeeklyTimeUsageChart(selectedWeekUsage) }
                items(week.days.size, key = { week.days[it].date.toEpochDay() }) {
                    val day = week.days[it]
                    if (day.sessions.isEmpty()) CompactEmptyDay(day) else DaySection(day, checkIns = checkIns)
                }
            }
            if (topicTotals.isNotEmpty()) item { TopicTotalsSection(topicTotals, enabled = !deleting) }
            if (summary != null) item { PlanSummary(summary) }
            item {
                PrimaryButton(
                    exportButtonText(exportCount),
                    onExport,
                    enabled = !deleting && exportBlock == null,
                    subtitle = exportBlock?.let(::exportBlockText) ?: EXPORT_SUBTITLE,
                )
            }
            item { SecondaryButton("남은 공부 다시 계획하기", onRemainingStudy, enabled = !deleting && remainingStudyReason == null) }
            item { SecondaryButton("다시 진단하기", onReassess, enabled = !deleting && reassessmentReason == null) }
            remainingStudyReason?.let { item { Note(it) } }
            reassessmentReason?.takeIf { it != remainingStudyReason }?.let { item { Note(it) } }
            regenerationReason?.let { item { Note(it) } }
            item { SecondaryButton("이 계획 다시 만들기", onRegenerate, enabled = !deleting && regenerationReason == null) }
            item { SecondaryButton("일정 조정하기", onAdjust, enabled = !deleting) }
            // With no sessions export is disabled, so starting over is the next step unless a storage retry is.
            val inputActionLeads = week == null && storage == PlanStorage.Ready
            inputAction?.let { action ->
                val onInput = if (action == PlanInputAction.EditInputs) onEditInputs else onCreate
                item {
                    if (inputActionLeads) PrimaryButton(action.label, onInput, enabled = !deleting)
                    else SecondaryButton(action.label, onInput, enabled = !deleting)
                }
            }
            item { SecondaryButton("처음으로", onHome, enabled = !deleting) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (deleteFailure != null) AlertCard(deleteFailure.title, deleteFailure.body)
                    val label = when {
                        deleting -> "삭제하는 중…"
                        deleteFailure != null -> "다시 시도"
                        else -> "계획 삭제"
                    }
                    SecondaryButton(label, onClick = {
                        if (deleteFailure != null) onDelete() else confirmingDeletion = true
                    }, enabled = !deleting && deletionBlockReason == null)
                    if (!deleting) deletionBlockReason?.let { Note(it) }
                }
            }
        }
    }
}

@Composable
private fun DeletePlanDialog(plan: SessionPlan, canDelete: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(max = 342.dp).fillMaxWidth(),
            shape = RoundedCornerShape(16.dp), color = Paper, border = BorderStroke(1.dp, Outline),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("계획을 삭제할까요?", style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp), color = Ink)
                    Text("계획 ${plan.ordinal} · $CERTIFICATION_NAME", style = MaterialTheme.typography.bodyMedium, color = Ink)
                }
                val where = if (plan.isDemo || plan.scheduleCsvFile == null) "이 기기에서" else "이 기기와 서버에서"
                Text("$where 계획과 공부 기록이 지워져요. 휴대폰 캘린더에 추가한 일정은 그대로예요.",
                    style = MaterialTheme.typography.bodyMedium, color = Ink)
                PrimaryButton("삭제", onConfirm, enabled = canDelete)
                TextButton(onCancel, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                    Text("취소", style = MaterialTheme.typography.labelLarge, color = Ink, textDecoration = TextDecoration.Underline)
                }
            }
        }
    }
}

/** The selected week's study-session count and summed duration; existing schedules are not counted. */
@Composable
private fun WeeklyStudyTotals(week: WeekPlan) {
    Text(
        "공부 일정 ${week.studySessionCount}개 · ${PlanGrouping.formatDuration(week.studyMinutes)}",
        style = MaterialTheme.typography.bodyMedium,
        color = InkBody,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Whole-plan study time per returned topic (frames 2384:4326 collapsed, 2384:4353 expanded):
 * collapsed by default so the week's schedule stays first; the header toggles the topic rows.
 */
@Composable
private fun TopicTotalsSection(totals: List<TopicTotal>, enabled: Boolean) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = "공부 주제 ${totals.size}개 · 총 ${PlanGrouping.formatDuration(totals.sumOf { it.minutes })}"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Paper)
            .border(1.5.dp, Outline, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("topic-totals"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = if (expanded) "접기" else "펼치기") { expanded = !expanded }
                .semantics(mergeDescendants = true) { stateDescription = if (expanded) "펼침" else "접힘" },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("주제별 공부 시간", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = InkBody)
            }
            Image(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.size(20.dp).rotate(if (expanded) -90f else 90f),
            )
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                totals.forEach { total ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(total.topic, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
                        Text(
                            PlanGrouping.formatDuration(total.minutes),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = Ink,
                            textAlign = TextAlign.End,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

/** A day without sessions as one muted row: weekday and date, with 「일정 없음」 wrapping on the right. */
@Composable
private fun CompactEmptyDay(day: DayPlan) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MutedSurface)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(dayLabel(day.date), style = MaterialTheme.typography.labelMedium, color = Ink)
        Text(monthDay(day.date), style = MaterialTheme.typography.bodySmall, color = InkSecondary)
        Text(
            "일정 없음",
            style = MaterialTheme.typography.bodySmall,
            color = InkMuted,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
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
        Note("회색은 가용 시간, 검정은 배정 시간이에요. 캘린더 일정과 겹치는 시간은 가용 시간에서 빠져요. 화살표를 눌러 주차별로 확인할 수 있어요.")
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
internal fun WeekNavigator(week: WeekPlan, index: Int, count: Int, onPrevious: () -> Unit, onNext: () -> Unit,
                           enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton44(R.drawable.ic_back, "이전 주", onPrevious, enabled = enabled && index > 0)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(weekRange(week), style = MaterialTheme.typography.labelMedium, color = InkBody, textAlign = TextAlign.Center)
            Text("${index + 1} / ${count}주", style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        }
        // The back arrow mirrored serves as the next arrow.
        Row(Modifier.scale(scaleX = -1f, scaleY = 1f)) {
            IconButton44(R.drawable.ic_back, "다음 주", onNext, enabled = enabled && index < count - 1)
        }
    }
}

private fun weekRange(week: WeekPlan): String {
    val end = week.sunday
    val endText = if (end.month == week.monday.month) "${end.dayOfMonth}일" else monthDay(end)
    return "${monthDay(week.monday)} – $endText"
}

internal fun monthDay(date: LocalDate) = "${date.monthValue}월 ${date.dayOfMonth}일"

internal fun dayLabel(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)

/**
 * One day of the plan. With [onSelect], study sessions become single-choice options with
 * [selectedId] marked; existing schedules stay plain rows. The study session [movedId] gets a
 * “옮긴 일정” tag. With [checkIns], every study session also shows its report and a separate
 * action to record it, outside the selectable option.
 */
@Composable
internal fun DaySection(
    day: DayPlan,
    selectedId: String? = null,
    onSelect: ((String) -> Unit)? = null,
    selectionEnabled: Boolean = true,
    movedId: String? = null,
    checkIns: CheckInRows? = null,
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
                        if (checkIns != null && !session.isExternal && removedSchedule == null) {
                            CheckInRowAction(session, sessionLabel(session, day.date), checkIns)
                        }
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
    val label = sessionLabel(session, date) +
        (if (moved) " $MOVED_TAG" else "") + (if (enabled) "" else " $SELECTION_LOCKED")
    // The chosen row says what it was chosen for, on screen and to assistive technology.
    ChoiceCard(
        selected = selected,
        onClick = onClick,
        choiceLabel = label,
        enabled = enabled,
        modifier = if (selected) Modifier.semantics { stateDescription = SELECTED_STATE } else Modifier,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SessionRow(session, moved, highlighted = highlighted, chosen = selected)
            if (!enabled) Text(SELECTION_LOCKED, style = MaterialTheme.typography.labelSmall, color = InkSecondary)
        }
    }
}

/** Date, weekday, time and topic that name one session to assistive technology. */
internal fun sessionLabel(session: PlanSession, date: LocalDate) =
    "${monthDay(date)} ${dayLabel(date)} ${sessionTime(session)} ${session.topic}"

internal fun sessionTime(session: PlanSession) =
    "${WireDateTime.formatTime(session.start.toLocalTime())}–${WireDateTime.formatTime(session.end.toLocalTime())}"

@Composable
private fun SessionRow(
    session: PlanSession,
    moved: Boolean = false,
    removed: Boolean = false,
    removedDestination: PlanSession? = null,
    highlighted: Boolean = false,
    onNavigate: (() -> Unit)? = null,
    chosen: Boolean = false,
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
            Text(
                sessionTime(session),
                style = MonoStyles.number.copy(textDecoration = if (removed) TextDecoration.LineThrough else TextDecoration.None),
                color = if (removed) InkSecondary else InkBody,
            )
            if (session.isExternal) Tag("기존 일정")
            if (moved) Tag(MOVED_TAG)
            if (chosen) Tag(SELECTED_TAG)
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
private const val SELECTED_TAG = "조정할 일정"
private const val SELECTED_STATE = "조정할 일정으로 선택됨"
