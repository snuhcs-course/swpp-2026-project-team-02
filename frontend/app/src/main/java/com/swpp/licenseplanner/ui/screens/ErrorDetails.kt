package com.swpp.licenseplanner.ui.screens

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.swpp.licenseplanner.data.ApiError
import com.swpp.licenseplanner.ui.developerMessage
import com.swpp.licenseplanner.ui.components.MutedBox
import com.swpp.licenseplanner.ui.components.SecondaryButton
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkBody
import com.swpp.licenseplanner.ui.theme.MonoStyles

/** Opt-in disclosure for the original diagnostic; it is hidden from the normal user-facing error. */
@Composable
fun ErrorDetails(error: ApiError) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SecondaryButton(
        text = if (expanded) "세부 메시지 닫기" else "세부 메시지 보기",
        onClick = { expanded = !expanded },
    )
    if (expanded) {
        MutedBox {
            Text("개발자용 원본 오류 메시지", style = MaterialTheme.typography.titleSmall, color = Ink)
            SelectionContainer {
                Text(error.developerMessage(), style = MonoStyles.tag, color = InkBody)
            }
        }
    }
}
