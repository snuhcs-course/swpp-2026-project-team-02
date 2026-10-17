package com.swpp.licenseplanner.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.domain.WireDateTime
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.OutlinedCard
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.components.Tag
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkBody
import com.swpp.licenseplanner.ui.theme.InkSecondary
import com.swpp.licenseplanner.ui.theme.Outline
import com.swpp.licenseplanner.ui.theme.Paper

/**
 * Plans generated in this app process, distinguished by ordinal and dates.
 * The list needs no server route and is lost when the process ends. A visual extension of
 * the existing card components; no Figma frame exists.
 */
@Composable
fun PlanListScreen(plans: List<SessionPlan>, onOpen: (Int) -> Unit, onCreate: () -> Unit, onBack: () -> Unit) {
    AppScreen(
        title = "조정할 계획",
        onNav = onBack,
        footer = {
            if (plans.isEmpty()) PrimaryButton("계획 생성", onCreate) else SecondaryButton("새 계획 만들기", onCreate)
        },
    ) {
        Heading("어떤 계획을 조정할까요?", "앱을 실행한 동안 만든 계획이에요. 앱을 완전히 종료하면 목록이 사라져요.")
        if (plans.isEmpty()) {
            OutlinedCard {
                Text("아직 만든 계획이 없어요", style = MaterialTheme.typography.titleSmall, color = Ink)
                Note("계획을 먼저 만들면 여기에서 골라 조정할 수 있어요.")
            }
        } else {
            for (plan in plans) PlanRow(plan) { onOpen(plan.ordinal) }
        }
    }
}

@Composable
private fun PlanRow(plan: SessionPlan, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = Paper,
        border = BorderStroke(1.5.dp, Outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("계획 ${plan.ordinal} · $CERTIFICATION_NAME", style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(
                "준비 ${wireDate(plan.original.preparationStart)} – 시험 ${wireDate(plan.original.examDate)}",
                style = MaterialTheme.typography.bodyMedium,
                color = InkBody,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("공부 일정 ${plan.studyCount}개", style = MaterialTheme.typography.bodySmall, color = InkSecondary)
                if (!plan.hasIdentity) Tag("조정 불가")
            }
        }
    }
}

/** `2026년 10월 5일` from a request timestamp. */
internal fun wireDate(value: String): String =
    WireDateTime.parseOrNull(value)?.toLocalDate()?.let { "${it.year}년 ${monthDay(it)}" } ?: value
