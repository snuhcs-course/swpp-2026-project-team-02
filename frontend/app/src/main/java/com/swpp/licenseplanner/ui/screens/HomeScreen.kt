package com.swpp.licenseplanner.ui.screens

import androidx.compose.runtime.Composable
import com.swpp.licenseplanner.domain.CERTIFICATION_NAME
import com.swpp.licenseplanner.domain.SessionPlan
import com.swpp.licenseplanner.ui.PlanStorage
import com.swpp.licenseplanner.ui.components.AlertCard
import com.swpp.licenseplanner.ui.components.AppScreen
import com.swpp.licenseplanner.ui.components.Heading
import com.swpp.licenseplanner.ui.components.LoadingBlock
import com.swpp.licenseplanner.ui.components.Note
import com.swpp.licenseplanner.ui.components.PrimaryButton
import com.swpp.licenseplanner.ui.components.SecondaryButton

/**
 * The first screen offers creation (the existing input flow) and adjustment (the
 * saved plan list). A visual extension of the existing components; no Figma frame exists.
 */
@Composable
fun HomeScreen(
    plans: List<SessionPlan>,
    savedPlans: List<SessionPlan>,
    storage: PlanStorage,
    onCreate: () -> Unit,
    onAdjust: () -> Unit,
    onRetryLoad: () -> Unit,
    onRetrySave: () -> Unit,
) {
    AppScreen(title = "학습 계획", navIcon = null) {
        Heading("무엇을 할까요?", "새 계획을 만들거나, 만든 계획에서 놓친 공부 일정을 다시 잡아요.")
        PrimaryButton("계획 생성", onCreate, subtitle = "$CERTIFICATION_NAME 필기")
        SecondaryButton("계획 조정", onAdjust)
        if (storage == PlanStorage.Loading) LoadingBlock(LOADING_SAVED_PLANS)
        StorageAlert(storage, onRetryLoad, onRetrySave)
        planCountNote(storage, plans.size, savedPlans.size)?.let { Note(it) }
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
            SecondaryButton("다시 불러오기", onRetryLoad)
        }
        PlanStorage.SaveFailure -> {
            AlertCard(
                "기기에 계획을 저장하지 못했어요",
                "화면에 보이는 마지막 변경은 아직 저장되지 않아 앱을 닫으면 사라질 수 있어요. " +
                    "저장하기 전에는 계획을 만들거나 조정할 수 없어요. 다시 저장해도 서버에 요청을 다시 보내지 않아요.",
            )
            SecondaryButton("다시 저장하기", onRetrySave)
        }
        else -> Unit
    }
}

internal const val LOADING_SAVED_PLANS = "저장된 계획을 불러오는 중이에요"
