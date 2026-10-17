package com.swpp.licenseplanner.ui.screens

import androidx.compose.runtime.Composable
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton

/**
 * REPLAN-01: the first screen offers creation (the existing input flow) and adjustment (the
 * session plan list). A visual extension of the existing components; no Figma frame exists.
 */
@Composable
fun HomeScreen(planCount: Int, onCreate: () -> Unit, onAdjust: () -> Unit) {
    AppScreen(title = "학습 계획", navIcon = null) {
        Heading("무엇을 할까요?", "새 계획을 만들거나, 만든 계획에서 놓친 공부 일정을 다시 잡아요.")
        PrimaryButton("계획 생성", onCreate, subtitle = "$CERTIFICATION_NAME 필기")
        SecondaryButton("계획 조정", onAdjust)
        Note(if (planCount == 0) "아직 만든 계획이 없어요." else "지금 실행 중에 만든 계획 ${planCount}개가 있어요.")
    }
}
