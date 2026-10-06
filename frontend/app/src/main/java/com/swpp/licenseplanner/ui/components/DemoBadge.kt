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

/** Top-bar label shown on every screen of the demo flavor (GEN-05). Renders nothing in live builds. */
@Composable
fun DemoBadge(modifier: Modifier = Modifier, demoMode: Boolean = BuildConfig.DEMO_MODE) {
    if (!demoMode) return
    Text(
        text = "DEMO 데모",
        style = MonoStyles.tag,
        color = Paper,
        modifier = modifier
            .semantics { contentDescription = "데모 모드: 로컬 시연용 데이터" }
            .background(Ink, AppShapes.extraSmall)
            .border(1.dp, Ink, AppShapes.extraSmall)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
