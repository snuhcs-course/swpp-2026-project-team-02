package com.swpp.licenseplanner.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.domain.LocalTopics
import com.swpp.licenseplanner.domain.PlanFormState
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
import com.swpp.licenseplanner.ui.theme.InkBody
import com.swpp.licenseplanner.ui.theme.MonoStyles
import com.swpp.licenseplanner.ui.userMessage

/**
 * FLOW-04, adapted from frame 2005:253: one loaded example question at a time with
 * loaded-count progress, the local example label, answer cards, and “모르겠음”.
 * Loading starts on first entry; the ViewModel ignores repeats after success.
 */
@Composable
fun QuestionsScreen(
    form: PlanFormState,
    loadState: QuestionLoadState,
    onLoad: () -> Unit,
    onAnswer: (questionId: Int, choiceKey: String) -> Unit,
    onExit: () -> Unit,
    onFinished: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoad() }
    val questions = form.questions
    var index by rememberSaveable { mutableIntStateOf(0) }
    val loaded = loadState == QuestionLoadState.Loaded && questions.isNotEmpty()
    BackHandler(enabled = loaded && index > 0) { index-- }

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
                    onClick = { if (isLast) onFinished() else index++ },
                )
            }
        } else null,
    ) {
        when {
            loadState is QuestionLoadState.Failure -> {
                Heading("예시 문항을 불러오지 못했어요", loadState.error.userMessage())
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
 * FLOW-05, adapted from frame 2005:287: earned/possible points per local example label.
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
        Heading("로컬 예시 문항 점수", "${questions.size}문항 중 ${correct}문항 정답. ‘모르겠음’은 0점이에요.")
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
        Note(
            "앱 안에서 예시 문항 번호로 나눈 단원과 점수예요. 서버의 통계가 아니며, " +
                "계획을 만드는 플래너는 주제를 다르게 분류할 수 있어요.",
        )
    }
}
