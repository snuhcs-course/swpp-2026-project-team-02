package com.swpp.licenseplanner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.BuildConfig
import com.swpp.licenseplanner.ui.theme.AppShapes
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.MonoStyles
import com.swpp.licenseplanner.ui.theme.Paper

/** Top-bar label shown on every screen of the demo flavor. Renders nothing in live builds. */
@Composable
fun DemoBadge(modifier: Modifier = Modifier, demoMode: Boolean = BuildConfig.DEMO_MODE) {
    if (!demoMode && !BuildConfig.INTEGRATION_DEMO) return
    Text(
        text = if (BuildConfig.INTEGRATION_DEMO) "HTTP 통합 데모 · AI 아님" else "DEMO 데모",
        style = MonoStyles.tag,
        color = Paper,
        modifier = modifier
            .semantics { contentDescription = if (BuildConfig.INTEGRATION_DEMO) "서버 연동 시연 모드, AI 결과 아님" else "데모 모드: 로컬 시연용 데이터" }
            .background(Ink, AppShapes.extraSmall)
            .border(1.dp, Ink, AppShapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
