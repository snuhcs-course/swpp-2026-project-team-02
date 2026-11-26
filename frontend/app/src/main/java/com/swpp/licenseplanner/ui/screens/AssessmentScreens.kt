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
 * Adapted from frame 2005:253: one loaded example question at a time with
 * loaded-count progress, the local example label, answer cards, and “모르겠음”.
 * Loading starts on first entry; the ViewModel ignores repeats after success.
 */
@Composable
fun QuestionsScreen(
    form: PlanFormState,
    loadState: QuestionLoadState,
    onLevel: (com.swpp.licenseplanner.domain.CurrentLevel) -> Unit,
    onLoad: () -> Unit,
    onAnswer: (questionId: Int, choiceKey: String) -> Unit,
    onExit: () -> Unit,
    onFinished: () -> Unit,
) {
    LaunchedEffect(Unit) { onLoad() }
    val questions = form.questions
    val loaded = loadState == QuestionLoadState.Loaded && questions.isNotEmpty()
    AppScreen(title = "학습 계획 생성하기", onNav = onExit, footer = {
        PrimaryButton("다음", onFinished, enabled = loaded && form.level != null && questions.all { it.id in form.answers })
    }) {
        CreationHeading(2, "지금 실력을 확인해볼까요?", "예시 진단 문항에 답해주세요. 모르는 문항은 ‘모르겠음’을 선택하세요.")
        Text(com.swpp.licenseplanner.domain.CERTIFICATION_NAME)
        Text("현재 실력")
        for (level in com.swpp.licenseplanner.domain.CurrentLevel.entries) {
            CreationOption(level.label, form.level == level) { onLevel(level) }
        }
        when {
            loadState is QuestionLoadState.Failure -> {
                Heading("문항을 불러오지 못했어요", loadState.error.userMessage())
                ErrorDetails(loadState.error)
                PrimaryButton("다시 시도", onLoad)
            }
            !loaded -> LoadingBlock("예시 문항을 불러오는 중이에요")
            else -> questions.forEachIndexed { number, question ->
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("${(number + 1).toString().padStart(2, '0')}   ${question.prompt}", style = MaterialTheme.typography.titleSmall)
                    for ((key, label) in question.answerChoices) {
                        CreationOption(label, form.answers[question.id] == key) { onAnswer(question.id, key) }
                    }
                    CreationOption(UNKNOWN_LABEL, form.answers[question.id] == UNKNOWN_KEY) { onAnswer(question.id, UNKNOWN_KEY) }
                }
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
    }
}
