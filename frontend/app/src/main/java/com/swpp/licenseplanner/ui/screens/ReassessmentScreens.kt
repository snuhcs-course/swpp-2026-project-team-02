package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.data.model.ScheduleRow
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.ReassessmentPreview
import com.swpp.licenseplanner.domain.Scoring
import com.swpp.licenseplanner.domain.Weekday
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.domain.formatPoints
import com.swpp.licenseplanner.ui.MoveSave
import com.swpp.licenseplanner.ui.PlanStorage
import com.swpp.licenseplanner.ui.REASSESSMENT_NOTHING_LEFT
import com.swpp.licenseplanner.ui.ReassessmentState
import com.swpp.licenseplanner.ui.ReassessmentStop
import com.swpp.licenseplanner.ui.TopicComparison
import com.swpp.licenseplanner.ui.adjustmentMessage
import com.swpp.licenseplanner.ui.changedPieces
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.LoadingBlock
import com.swpp.licenseplanner.ui.components.MutedBox
import com.swpp.licenseplanner.ui.components.NavIcon
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.OutlinedCard
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.ProgressTrack
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.holdsCommit
import com.swpp.licenseplanner.ui.previewMessage
import com.swpp.licenseplanner.ui.theme.Divider
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.MonoStyles
import com.swpp.licenseplanner.ui.theme.Outline
import com.swpp.licenseplanner.ui.topicComparisons
import com.swpp.licenseplanner.ui.userMessage

/** Quiz label: different items from the first diagnostic, authored, and not an official or validated diagnostic. */
internal const val REASSESSMENT_QUIZ_LABEL = "처음 진단과 다른 예시 문항이에요. 팀이 직접 만든 예시이며 실제 기출이나 " +
    "공식·검증된 진단이 아니에요. 모르면 ‘모르겠음’을 골라 주세요. 0점으로 계산돼요."

internal const val REASSESSMENT_RESULTS_LABEL = "처음 진단과 다른 예시 문항으로 낸 점수예요. 합격 가능성이나 실력을 " +
    "예측하지 않아요. 이 화면을 떠나면 결과는 저장되지 않아요."

internal const val REASSESSMENT_SERVER_UPDATE = "이 서버는 아직 다시 진단하기를 지원하지 않아요. 서버가 업데이트된 뒤 " +
    "다시 시도해 주세요. 다른 문항으로 대신하지 않았고 계획은 그대로예요."

/** What a reassessment never changes, shown with every preview. */
internal const val REASSESSMENT_UNCHANGED_NOTE = "공부 시간은 그대로이고 주제만 바뀌어요. 지난 공부와 이미 기록한 공부, " +
    "캘린더에 내보낸 일정은 바뀌지 않아요."

internal const val REASSESSMENT_CALENDAR_NOTE = "이전에 캘린더로 내보낸 일정은 예전 주제 그대로예요. 캘린더 내보내기는 " +
    "계획 화면에서 따로 할 수 있어요."

internal const val REASSESSMENT_NO_CHANGE = "바뀌는 남은 공부가 없어요"

internal fun reassessmentChangedText(count: Int): String = "남은 공부 ${count}개 일정의 주제를 바꿨어요"

/** Title and body for a preview or update that stopped. */
internal fun reassessmentStopText(state: ReassessmentState.Stopped): Pair<String, String> = when (state.reason) {
    ReassessmentStop.NothingLeft -> REASSESSMENT_NOTHING_LEFT to "지금부터 주제를 바꿀 수 있는 기록 없는 공부 일정이 없어요. 계획은 그대로예요."
    ReassessmentStop.MissingPlanContext -> "저장된 준비 정보가 없어요" to
        "서버에 이 계획의 준비 기간과 공부 시간이 저장되어 있지 않아요. 홈의 '계획 생성'에서 새 계획을 만들어 주세요."
    ReassessmentStop.NotEnoughTime -> "다시 나눌 남은 시간이 부족해요" to
        "남은 공부 시간이 진단한 주제 수보다 적어 주제별로 다시 나눌 수 없어요. 계획은 그대로예요."
    ReassessmentStop.Expired -> "미리 본 내용이 지난 내용이에요" to
        "확인하는 사이 바꿀 수 있던 공부 일정이 시작됐어요. 아무것도 바꾸거나 저장하지 않았어요. 같은 답으로 다시 미리 볼 수 있어요."
    ReassessmentStop.PreviewFailed -> "바뀔 내용을 미리 보지 못했어요" to
        "${(state.error ?: ApiError.InvalidResponse(null)).previewMessage()}\n아무것도 바꾸지 않았어요. 같은 답으로 다시 미리 볼 수 있어요."
    ReassessmentStop.CommitFailed -> "남은 공부를 바꾸지 못했어요" to when (val error = state.error) {
        is ApiError.ServerFailure, is ApiError.ServerUnavailable ->
            "바꾼 결과를 확인할 수 없어요. 서버에 반영되었을 수 있으니 최신 계획을 불러와 확인해 주세요."
        null -> "남은 공부를 바꾸지 못했어요. 계획은 그대로예요."
        else -> error.adjustmentMessage()
    }
}

/** “11월 26일 목요일 · 19:00–20:00” for a row of one day. */
internal fun reassessmentSessionTime(row: ScheduleRow): String {
    val start = WireDateTime.parseOrNull(row.startDate) ?: return "시간 확인 필요"
    val end = WireDateTime.parseOrNull(row.endDate) ?: return "시간 확인 필요"
    return "${start.monthValue}월 ${start.dayOfMonth}일 ${Weekday.of(start.dayOfWeek).label}요일 · " +
        "${WireDateTime.formatTime(start.toLocalTime())}–${WireDateTime.formatTime(end.toLocalTime())}"
}

private fun minutesOf(row: ScheduleRow): Long? {
    val start = WireDateTime.parseOrNull(row.startDate) ?: return null
    val end = WireDateTime.parseOrNull(row.endDate) ?: return null
    return java.time.Duration.between(start, end).toMinutes()
}

internal fun topicComparisonText(topic: TopicComparison): String =
    "처음 진단 ${topic.firstPercent?.let { "$it%" } ?: "기록 없음"} → 이번 진단 ${topic.newPercent}%"

/**
 * The reassessment flow above the plan view it started from: question loading, the quiz (frame
 * 2430:4726), the explained results (2432:4850), the preview and its outcome. Back and every
 * return action call [onClose], except while the update or its save runs, which cannot be left.
 * [onApply] asks for a preview, also again after an out-of-date or failed one.
 */
@Composable
fun ReassessmentScreen(
    state: ReassessmentState,
    onRetryLoad: () -> Unit,
    onAnswer: (questionId: Int, choiceKey: String) -> Unit,
    onShowQuestion: (Int) -> Unit,
    onFinishQuiz: () -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit,
    storage: PlanStorage = PlanStorage.Ready,
    onConfirm: () -> Unit = {},
    onRetrySave: () -> Unit = {},
    onShowPlan: () -> Unit = onClose,
) {
    val locked = state.holdsCommit
    BackHandler(enabled = locked) {}
    BackHandler(enabled = !locked, onBack = onClose)
    when (state) {
        is ReassessmentState.Quiz -> ReassessmentQuiz(state, onAnswer, onShowQuestion, onFinishQuiz, onClose)
        is ReassessmentState.Results -> ReassessmentResults(state, onApply, onClose)
        is ReassessmentState.Preview -> if (state.preview.hasChanges) {
            ReassessmentPreviewScreen(state, storage, onConfirm, onClose)
        } else ReassessmentShell(state, "남은 공부에 반영해 보기", onClose) {
            MutedBox {
                Text(REASSESSMENT_NO_CHANGE, style = MaterialTheme.typography.titleSmall, color = Ink)
                Note("이번 진단 점수로 나눠도 남은 공부의 주제가 지금과 같아요. 계획은 그대로예요.")
            }
            PrimaryButton("계획으로 돌아가기", onClose)
        }
        is ReassessmentState.Previewing -> ReassessmentShell(state, "남은 공부에 반영해 보기", onClose) {
            LoadingBlock("바뀔 내용을 계산하는 중이에요", "아직 아무것도 바꾸지 않았어요.")
        }
        is ReassessmentState.Committing -> ReassessmentShell(state, "남은 공부에 반영해 보기", null) {
            LoadingBlock("남은 공부의 주제를 바꾸는 중이에요", "완료될 때까지 기다려 주세요.")
        }
        is ReassessmentState.Stopped -> ReassessmentShell(state,
            if (state.reason == ReassessmentStop.CommitFailed) "다시 진단하기" else "남은 공부에 반영해 보기", onClose) {
            val (title, body) = reassessmentStopText(state)
            AlertCard(title, body)
            state.error?.let { ErrorDetails(it) }
            if (state.canPreviewAgain) {
                PrimaryButton("다시 미리 보기", onApply, enabled = storage == PlanStorage.Ready)
                SecondaryButton("계획으로 돌아가기", onClose)
            } else PrimaryButton("계획으로 돌아가기", onClose)
        }
        is ReassessmentState.Done -> ReassessmentDone(state, onRetrySave, onShowPlan, onClose)
        is ReassessmentState.Invalid -> ReassessmentShell(state, "다시 진단하기", onClose) {
            AlertCard("다시 진단해 주세요", state.message)
            if (storage == PlanStorage.SaveFailure) PrimaryButton("다시 저장하기", onRetrySave)
            SecondaryButton("계획으로 돌아가기", onClose, enabled = storage != PlanStorage.Saving)
        }
        else -> AppScreen("다시 진단하기", navIcon = NavIcon.Close, onNav = onClose) {
            Heading("계획 ${state.source.ordinal} · $CERTIFICATION_NAME")
            when (state) {
                is ReassessmentState.Loading -> LoadingBlock("예시 문항을 불러오는 중이에요")
                is ReassessmentState.LoadFailure -> {
                    AlertCard("예시 문항을 불러오지 못했어요", state.error.userMessage())
                    ErrorDetails(state.error)
                    Note("계획은 그대로예요.")
                    PrimaryButton("다시 시도", onRetryLoad)
                    SecondaryButton("계획으로 돌아가기", onClose)
                }
                is ReassessmentState.ServerNeedsUpdate -> {
                    AlertCard("서버 업데이트가 필요해요", REASSESSMENT_SERVER_UPDATE)
                    PrimaryButton("계획으로 돌아가기", onClose)
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ReassessmentQuiz(
    state: ReassessmentState.Quiz,
    onAnswer: (Int, String) -> Unit,
    onShowQuestion: (Int) -> Unit,
    onFinish: () -> Unit,
    onClose: () -> Unit,
) {
    val index = state.index.coerceIn(0, state.questions.lastIndex)
    val item = state.questions[index]
    val selected = state.answers[item.question.id]
    val isLast = index == state.questions.lastIndex
    AppScreen(
        title = "다시 진단하기",
        navIcon = NavIcon.Close,
        onNav = onClose,
        footer = {
            PrimaryButton(
                text = if (isLast) "결과 보기" else "다음",
                enabled = selected != null,
                onClick = { if (isLast) onFinish() else onShowQuestion(index + 1) },
            )
        },
    ) {
        QuizQuestion(item.question, index, state.questions.size, item.topic, selected, REASSESSMENT_QUIZ_LABEL, onAnswer)
        if (state.source.isDemo) Note("로컬 시연 문항 · 서버나 AI를 사용하지 않아요.")
    }
}

@Composable
private fun ReassessmentResults(state: ReassessmentState.Results, onApply: () -> Unit, onClose: () -> Unit) {
    val questions = state.questions.map { it.question }
    val correct = questions.count { Scoring.isCorrect(it, state.answers[it.id]) }
    AppScreen(
        title = "다시 진단 결과",
        onNav = onClose,
        footer = {
            PrimaryButton("남은 공부에 반영해 보기", onApply)
            SecondaryButton("계획으로 돌아가기", onClose)
        },
    ) {
        Heading("다시 진단 예시 문항 점수", "${questions.size}문항 중 ${correct}문항 정답. ‘모르겠음’은 0점이에요.")
        OutlinedCard {
            for (topic in topicComparisons(state)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(topic.topic, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
                        Text("${formatPoints(topic.earned)}/${formatPoints(topic.possible)}", style = MonoStyles.number, color = Ink)
                    }
                    ProgressTrack(if (topic.possible > 0) (topic.earned / topic.possible).toFloat() else 0f)
                    Note(topicComparisonText(topic))
                }
            }
        }
        Note(REASSESSMENT_RESULTS_LABEL)
        if (state.source.isDemo) Note("로컬 시연 계산 · 서버나 AI를 사용하지 않아요.")
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("문항별 결과", style = MaterialTheme.typography.titleSmall, color = Ink)
            Column(
                Modifier.fillMaxWidth().border(1.5.dp, Outline, MaterialTheme.shapes.large)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                state.questions.forEachIndexed { index, item ->
                    val question = item.question
                    if (index > 0) HorizontalDivider(color = Divider)
                    QuestionResultRow(index + 1, question.prompt, Scoring.questionResult(question, state.answers.getValue(question.id)),
                        correctAnswer = "${question.correctAnswer}. ${question.answerChoices.getValue(question.correctAnswer)}",
                        explanation = item.explanation)
                }
            }
        }
    }
}

/** Heading, demo label and [content] under one top bar; a null [onNav] hides the navigation icon. */
@Composable
private fun ReassessmentShell(
    state: ReassessmentState,
    title: String,
    onNav: (() -> Unit)?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    AppScreen(title, navIcon = if (onNav == null) null else NavIcon.Back, onNav = onNav ?: {}) {
        Heading("계획 ${state.source.ordinal} · $CERTIFICATION_NAME")
        if (state.source.isDemo) Note("로컬 시연 계산 · 서버나 AI를 사용하지 않아요.")
        content()
    }
}

/** Topic minutes before and after, every changed session and the confirm footer (frame 2432:4962). */
@Composable
private fun ReassessmentPreviewScreen(
    state: ReassessmentState.Preview,
    storage: PlanStorage,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    val preview = state.preview
    val pieces = changedPieces(preview)
    AppScreen(
        title = "남은 공부에 반영해 보기",
        onNav = onClose,
        footer = {
            PrimaryButton("이대로 바꾸기", onConfirm, enabled = storage == PlanStorage.Ready)
            SecondaryButton("바꾸지 않기", onClose)
        },
    ) {
        Heading("계획 ${state.source.ordinal} · $CERTIFICATION_NAME")
        if (state.source.isDemo) Note("로컬 시연 계산 · 서버나 AI를 사용하지 않아요.")
        Text("바꿀 수 있는 남은 공부 ${preview.totalMinutes}분", style = MaterialTheme.typography.titleMedium, color = Ink)
        ReassessmentTopicMinutes(preview)
        Text("바뀌는 일정 ${pieces.size}개", style = MaterialTheme.typography.titleSmall, color = Ink)
        for ((before, piece) in pieces) {
            MutedBox {
                Text(reassessmentSessionTime(piece), style = MaterialTheme.typography.titleSmall, color = Ink)
                Note(if (before == piece.topic) "$before · 주제 그대로" else "$before → ${piece.topic}")
            }
        }
        Note(REASSESSMENT_UNCHANGED_NOTE)
    }
}

@Composable
private fun ReassessmentTopicMinutes(preview: ReassessmentPreview) {
    OutlinedCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("주제별 남은 공부 시간", style = MaterialTheme.typography.labelLarge, color = Ink)
            Note("지금 → 바꾼 뒤 · 합계 ${preview.topics.sumOf { it.before }}분 → ${preview.totalMinutes}분")
        }
        HorizontalDivider(color = Divider)
        for (topic in preview.topics) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(topic.topic, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
                Text("${topic.before}분 → ${topic.after}분", style = MonoStyles.number, color = Ink)
            }
        }
    }
}

/** The changed sessions and their save state (frames 2433:4767 and 2433:4802). */
@Composable
private fun ReassessmentDone(
    state: ReassessmentState.Done,
    onRetrySave: () -> Unit,
    onShowPlan: () -> Unit,
    onClose: () -> Unit,
) {
    val pieces = changedPieces(state.preview)
    val saving = state.save == MoveSave.Saving
    ReassessmentShell(state, "다시 진단하기", if (saving) null else onClose) {
        if (state.save == MoveSave.Unsaved) {
            AlertCard(reassessmentChangedText(pieces.size), remainingSaveText(state.save))
        } else MutedBox {
            Text(reassessmentChangedText(pieces.size), style = MaterialTheme.typography.titleSmall, color = Ink)
            Note(remainingSaveText(state.save))
        }
        for ((_, piece) in pieces) {
            MutedBox {
                Text(minutesOf(piece)?.let { "${piece.topic} · ${it}분" } ?: piece.topic,
                    style = MaterialTheme.typography.titleSmall, color = Ink)
                Note(reassessmentSessionTime(piece))
            }
        }
        Note(REASSESSMENT_CALENDAR_NOTE)
        if (state.save == MoveSave.Unsaved) {
            Note("다시 저장하면 이미 바꾼 결과를 이 기기에 저장해요. 서버에 다시 요청하지 않아요.")
            PrimaryButton("다시 저장하기", onRetrySave)
            SecondaryButton("계획 보기", onShowPlan)
        } else PrimaryButton("계획 보기", onShowPlan, enabled = !saving)
    }
}
