package com.swpp.licenseplanner.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.swpp.licenseplanner.ui.DraftChoice
import com.swpp.licenseplanner.ui.DraftDecision
import com.swpp.licenseplanner.ui.DraftPhase
import com.swpp.licenseplanner.ui.theme.Paper

/** Explicit local save/discard decisions; all content scrolls at large text or in landscape. */
@Composable
fun CreationExitDialog(
    startingNew: Boolean,
    decision: DraftDecision?,
    waiting: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onStay: () -> Unit,
) {
    val writing = decision?.phase == DraftPhase.Writing || decision?.phase == DraftPhase.Done
    Dialog(onDismissRequest = onStay, properties = DialogProperties(dismissOnBackPress = !writing, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.large, color = Paper) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Heading(if (startingNew) "새 계획을 만들까요?" else "입력한 내용을 어떻게 할까요?")
                when (decision?.phase) {
                    DraftPhase.Writing, DraftPhase.Done -> LoadingBlock("기기에 저장하는 중이에요")
                    DraftPhase.Failed -> {
                        AlertCard(
                            if (decision.choice == DraftChoice.Save) "임시 저장하지 못했어요" else "입력을 버리지 못했어요",
                            "입력한 내용과 이전에 저장한 내용은 그대로 있어요. 다시 저장해도 계획 생성 요청은 보내지 않아요.",
                        )
                        PrimaryButton("다시 저장하기", onRetry)
                        SecondaryButton(if (startingNew) "취소" else "계속 작성하기", onStay)
                    }
                    null -> {
                        if (waiting) Note("진행 중인 작업이 끝나거나 권한 창을 닫은 뒤 다시 선택해 주세요.")
                        if (startingNew) {
                            Note("임시 저장한 입력이 있어요. 새로 만들면 저장한 입력을 버려요. 만든 계획과 공부 기록은 그대로 남아요.")
                            PrimaryButton("저장한 입력 이어서 만들기", onResume)
                            SecondaryButton("입력 버리고 새로 만들기", onDiscard)
                            SecondaryButton("취소", onStay)
                        } else {
                            Note("이 기기에 임시 저장하면 홈의 이어서 만들기에서 계속할 수 있어요. 마지막으로 저장한 입력만 앱을 다시 열어도 남아요.")
                            PrimaryButton("임시 저장하고 나가기", onSave)
                            SecondaryButton("입력 버리고 나가기", onDiscard)
                            SecondaryButton("계속 작성하기", onStay)
                        }
                    }
                }
            }
        }
    }
}
