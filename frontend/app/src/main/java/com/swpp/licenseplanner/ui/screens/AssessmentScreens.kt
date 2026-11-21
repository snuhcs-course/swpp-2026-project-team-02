package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.swpp.licenseplanner.R
import com.swpp.licenseplanner.domain.LocalTopics
import com.swpp.licenseplanner.domain.PlanFormState
import com.swpp.licenseplanner.domain.QuestionResult
import com.swpp.licenseplanner.domain.Scoring
import com.swpp.licenseplanner.domain.UNKNOWN_KEY
import com.swpp.licenseplanner.domain.UNKNOWN_LABEL
import com.swpp.licenseplanner.domain.formatPoints
import com.swpp.licenseplanner.ui.QuestionLoadState
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.ChoiceCard
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.LoadingBlock
import com.swpp.licenseplanner.ui.components.NavIcon
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.OutlinedCard
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.ProgressTrack
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.CorrectGreen
import com.swpp.licenseplanner.ui.theme.Divider
import com.swpp.licenseplanner.ui.theme.InkBody
import com.swpp.licenseplanner.ui.theme.InkSecondary
import com.swpp.licenseplanner.ui.theme.MonoStyles
import com.swpp.licenseplanner.ui.theme.Outline
import com.swpp.licenseplanner.ui.theme.WrongRed
import com.swpp.licenseplanner.ui.userMessage

/**
 * Adapted from frame 2005:253: one loaded example question at a time with
 * loaded-count progress, the local example label, answer cards, and “모르겠음”.
 * Loading starts on first entry; the ViewModel ignores repeats after success.
 */
@Composable
fun QuestionsScreen(
    form: PlanFormState,
    loadState: QuestionLoadState,
    index: Int,
    onShowQuestion: (Int) -> Unit,
    onLoad: () -> Unit,
    onAnswer: (questionId: Int, choiceKey: String) -> Unit,
    onExit: () -> Unit,
    onFinished: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoad() }
    val questions = form.questions
    val loaded = loadState == QuestionLoadState.Loaded && questions.isNotEmpty()
    BackHandler(enabled = loaded && index > 0) { onShowQuestion(index - 1) }

    val question = questions.getOrNull(index.coerceAtMost(questions.lastIndex))
    val selected = question?.let { form.answers[it.id] }
    val isLast = index >= questions.lastIndex

    AppScreen(
        title = "예시 문항",
        navIcon = NavIcon.Close,
        onNav = onExit,
        footer = if (loaded && question != null) {
            {
                PrimaryButton(
                    text = if (isLast) "결과 보기" else "다음",
                    enabled = selected != null,
                    onClick = { if (isLast) onFinished() else onShowQuestion(index + 1) },
                )
            }
        } else null,
    ) {
        when {
            loadState is QuestionLoadState.Failure -> {
                Heading("예시 문항을 불러오지 못했어요", loadState.error.userMessage())
                ErrorDetails(loadState.error)
                Note("입력한 시험, 날짜, 실력은 그대로 있어요.")
                PrimaryButton("다시 시도", onLoad)
                SecondaryButton("이전으로", onExit)
            }
            !loaded || question == null -> LoadingBlock("예시 문항을 불러오는 중이에요")
            else -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${questions.size}문항 중 ${index + 1}번째",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkBody,
                            modifier = Modifier.weight(1f),
                        )
                        Text("예시 단원: ${LocalTopics.labelFor(question.id)}", style = MaterialTheme.typography.bodySmall, color = InkBody)
                    }
                    ProgressTrack((index + 1).toFloat() / questions.size)
                }
                Text(question.prompt, style = MaterialTheme.typography.titleLarge, color = Ink)
                // The answer cards and “모르겠음” form one single-choice group driven by form.answers.
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        for ((key, label) in question.answerChoices) {
                            ChoiceCard(selected = selected == key, onClick = { onAnswer(question.id, key) }, choiceLabel = label) {
                                Text(
                                    label,
                                    style = if (label.all { it.code < 128 }) MonoStyles.answer else MaterialTheme.typography.bodyLarge,
                                    color = Ink,
                                )
                            }
                        }
                    }
                    SecondaryButton(UNKNOWN_LABEL, onClick = { onAnswer(question.id, UNKNOWN_KEY) }, selected = selected == UNKNOWN_KEY)
                }
                Note("예시로 만든 문항이에요. 실제 기출이나 검증된 진단이 아니에요. 모르면 ‘모르겠음’을 골라 주세요. 0점으로 계산돼요.")
            }
        }
    }
}

/**
 * Adapted from frame 2005:287: earned/possible points per local example label.
 * The AI time, practical readiness and estimate cards are intentionally omitted.
 */
@Composable
fun ResultsScreen(form: PlanFormState, onBack: () -> Unit, onContinue: () -> Unit) {
    val questions = form.questions
    val correct = questions.count { Scoring.isCorrect(it, form.answers[it.id]) }
    AppScreen(
        title = "결과",
        onNav = onBack,
        footer = { PrimaryButton("공부 시간 정하기", onContinue) },
    ) {
        Heading("예시 문항 결과", "${questions.size}문항 중 ${correct}문항 정답. ‘모르겠음’은 0점이에요.")
        OutlinedCard {
            for (score in Scoring.topicScores(questions, form.answers)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(score.topic, style = MaterialTheme.typography.bodyMedium, color = Ink, modifier = Modifier.weight(1f))
                        Text("${formatPoints(score.earned)}/${formatPoints(score.possible)}", style = MonoStyles.number, color = Ink)
                    }
                    ProgressTrack((score.earned / score.possible).toFloat())
                }
            }
        }
        Note("단원별 예시 문항 점수예요. 공부 계획의 단원 구분은 이 결과와 다를 수 있어요.")
        if (Scoring.allAnswered(questions, form.answers)) {
            Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("문항별 결과", style = MaterialTheme.typography.titleSmall, color = Ink)
                Column(
                    Modifier.fillMaxWidth().border(1.5.dp, Outline, MaterialTheme.shapes.large)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    questions.forEachIndexed { index, question ->
                        if (index > 0) HorizontalDivider(color = Divider)
                        QuestionResultRow(index + 1, question.prompt, Scoring.questionResult(question, form.answers.getValue(question.id)))
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionResultRow(number: Int, prompt: String, result: QuestionResult) {
    val (status, chosen, correct) = when (result) {
        QuestionResult.Correct -> Triple("정답", null, null)
        is QuestionResult.Wrong -> Triple("오답", result.chosen, result.correct)
        is QuestionResult.Unknown -> Triple(UNKNOWN_LABEL, UNKNOWN_LABEL, result.correct)
    }
    val color = if (result == QuestionResult.Correct) CorrectGreen else WrongRed
    val icon = if (result == QuestionResult.Correct) R.drawable.ic_result_correct else R.drawable.ic_result_wrong
    Column(
        Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = "문항 $number $status" +
                (chosen?.let { ", 내 답 $it, 정답 $correct" } ?: "") + ", $prompt"
        }.padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("문항 $number", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Ink)
            Text(prompt, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = InkBody)
            Row(
                Modifier.border(1.dp, color, RoundedCornerShape(4.dp))
                    .padding(start = 4.dp, end = 6.dp, top = 1.dp, bottom = 1.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(icon), contentDescription = null, Modifier.size(14.dp), tint = color)
                Text(status, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = color)
            }
        }
        if (chosen != null && correct != null) {
            val style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = InkSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("내 답 $chosen", Modifier.weight(1f, fill = false), style = style,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("·", style = style)
                Text("정답 $correct", Modifier.weight(1f, fill = false), style = style,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
