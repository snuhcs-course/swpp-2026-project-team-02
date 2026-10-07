package com.swpp.licenseplanner.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.swpp.licenseplanner.R
import com.swpp.licenseplanner.ui.theme.ControlBorder
import com.swpp.licenseplanner.ui.theme.Divider
import com.swpp.licenseplanner.ui.theme.Ink
import com.swpp.licenseplanner.ui.theme.InkBody
import com.swpp.licenseplanner.ui.theme.InkMuted
import com.swpp.licenseplanner.ui.theme.InkSecondary
import com.swpp.licenseplanner.ui.theme.MonoStyles
import com.swpp.licenseplanner.ui.theme.MutedSurface
import com.swpp.licenseplanner.ui.theme.OnInkSecondary
import com.swpp.licenseplanner.ui.theme.Outline
import com.swpp.licenseplanner.ui.theme.Paper

/** Leading top-bar action from the Figma frames: back arrow or close. */
enum class NavIcon(@param:DrawableRes val drawable: Int, val description: String) {
    Back(R.drawable.ic_back, "뒤로"),
    Close(R.drawable.ic_close, "닫기"),
}

/**
 * Screen frame shared by every step (design D10): top bar with the demo badge,
 * scrollable content padded 20dp, and an optional footer above a divider.
 */
@Composable
fun AppScreen(
    title: String,
    navIcon: NavIcon? = NavIcon.Back,
    onNav: () -> Unit = {},
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        containerColor = Paper,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = { TopBar(title, navIcon, onNav) },
        bottomBar = { if (footer != null) Footer(footer) },
    ) { padding ->
        val base = Modifier.padding(padding).fillMaxSize()
        Column(
            modifier = (if (scrollable) base.verticalScroll(rememberScrollState()) else base)
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
fun TopBar(title: String, navIcon: NavIcon?, onNav: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = 56.dp)
            .padding(start = 8.dp, end = 12.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navIcon == null || navIcon == NavIcon.Close) Spacer(Modifier.width(12.dp))
        if (navIcon == NavIcon.Back) IconButton44(navIcon.drawable, navIcon.description, onNav)
        Text(title, style = MaterialTheme.typography.titleSmall, color = Ink, modifier = Modifier.weight(1f))
        DemoBadge()
        trailing()
        if (navIcon == NavIcon.Close) IconButton44(navIcon.drawable, navIcon.description, onNav)
    }
}

@Composable
private fun Footer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .drawBehind { drawLine(Divider, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
fun IconButton44(@DrawableRes icon: Int, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(icon),
            contentDescription = description,
            alpha = if (enabled) 1f else 0.3f,
        )
    }
}

/** Heading block: 24sp title and optional 15sp secondary text. */
@Composable
fun Heading(title: String, subtitle: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Ink)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = InkSecondary)
    }
}

/** Filled Ink button, 50dp minimum height, with an optional second line. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, subtitle: String? = null) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = if (enabled) Ink else Divider,
        contentColor = if (enabled) Paper else InkMuted,
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = if (enabled) OnInkSecondary else InkMuted)
            }
        }
    }
}

/**
 * White button with a 1.5dp Ink border. A non-null [selected] makes it a single-choice
 * option that is filled when selected (used for “모르겠음”); null keeps button semantics.
 */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, selected: Boolean? = null) {
    val filled = selected == true
    OptionSurface(
        choice = selected?.let { SingleChoice(text, it) },
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = if (filled) Ink else Paper,
        contentColor = if (filled) Paper else if (enabled) Ink else InkMuted,
        border = BorderStroke(1.5.dp, if (enabled) Ink else Outline),
        modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

/** Accessible label and state of one option in a single-choice group. */
class SingleChoice(val label: String, val selected: Boolean)

/**
 * Clickable [Surface], or with a [choice] a radio option. The option is one leaf
 * accessibility node holding its label, role and selection state: Compose reports the
 * RadioButton class only for nodes without semantic children, so the content's own
 * semantics (text, decorative indicator) are cleared.
 */
@Composable
private fun OptionSurface(
    choice: SingleChoice?,
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean = true,
    shape: Shape,
    color: Color,
    contentColor: Color = contentColorFor(color),
    border: BorderStroke,
    content: @Composable () -> Unit,
) {
    if (choice == null) {
        Surface(onClick, modifier, enabled, shape, color, contentColor, border = border, content = content)
    } else {
        Surface(
            modifier = modifier
                .clip(shape)
                .selectable(selected = choice.selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .clearAndSetSemantics { text = AnnotatedString(choice.label) },
            shape = shape,
            color = color,
            contentColor = contentColor,
            border = border,
            content = content,
        )
    }
}

/** 12dp-radius card with a 1.5dp outline, as in every Figma content card. */
@Composable
fun OutlinedCard(
    modifier: Modifier = Modifier,
    borderColor: Color = Outline,
    borderWidth: Dp = 1.5.dp,
    background: Color = Paper,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(background)
            .border(borderWidth, borderColor, MaterialTheme.shapes.large)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/**
 * Selectable option card; the selected card gets a 2dp Ink border and a filled indicator.
 * A non-null [choiceLabel] exposes it as a labeled radio option (assessment answers).
 */
@Composable
fun ChoiceCard(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    choiceLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    OptionSurface(
        choice = choiceLabel?.let { SingleChoice(it, selected) },
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = Paper,
        border = BorderStroke(if (selected) 2.dp else 1.5.dp, if (selected) Ink else Outline),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnswerIndicator(selected)
            content()
        }
    }
}

/** The Figma answer ellipse; a selected answer adds an inner Ink dot. */
@Composable
fun AnswerIndicator(selected: Boolean) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_answer_indicator), contentDescription = null)
        if (selected) Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Ink))
    }
}

/** Label on the left, SemiBold value on the right (Figma info rows). */
@Composable
fun InfoRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = InkSecondary)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            color = Ink,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 6dp track with an Ink fill (question progress and score bars). */
@Composable
fun ProgressTrack(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Divider),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).background(Ink))
    }
}

/** Small outlined tag (4dp corners), e.g. “AI” or “기존 일정”. */
@Composable
fun Tag(text: String, mono: Boolean = false) {
    Text(
        text,
        style = if (mono) MonoStyles.tag else MaterialTheme.typography.labelSmall.copy(
            fontSize = MonoStyles.tag.fontSize,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        ),
        color = Ink,
        modifier = Modifier
            .border(1.dp, Ink, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Dashed “AI output” box from the Figma frames (1.5dp #5A5A5A, 8dp corners). */
@Composable
fun DashedBox(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawRoundRect(
                    color = InkMuted,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** Muted gray note box (Figma #F3F3F3 summary cards). */
@Composable
fun MutedBox(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MutedSurface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun Note(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = InkSecondary, modifier = modifier)
}

@Composable
fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = InkBody)
}

/** Centered spinner and message for loading states. */
@Composable
fun LoadingBlock(message: String, detail: String? = null) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(color = Ink, trackColor = Divider, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
        Text(message, style = MaterialTheme.typography.titleSmall, color = Ink, textAlign = TextAlign.Center)
        if (detail != null) Note(detail)
    }
}

/** Gray-filled alert card with a 2dp Ink border and the Figma alert icon (frame 2005:474). */
@Composable
fun AlertCard(title: String, body: String) {
    OutlinedCard(borderColor = Ink, borderWidth = 2.dp, background = MutedSurface) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(painterResource(R.drawable.ic_alert), contentDescription = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = Ink)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = InkBody)
            }
        }
    }
}

/** Bordered input-like box (Figma date field: #8A8A8A 1.5dp, 8dp corners, 48dp). */
@Composable
fun InputBox(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = Paper,
        border = BorderStroke(1.5.dp, ControlBorder),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Ink)
        }
    }
}

/** 44dp square stepper button with the Figma minus/plus icons. */
@Composable
fun StepperButton(@DrawableRes icon: Int, description: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        color = Paper,
        border = BorderStroke(1.5.dp, if (enabled) ControlBorder else Divider),
        modifier = Modifier.size(44.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(painterResource(icon), contentDescription = description, alpha = if (enabled) 1f else 0.3f)
        }
    }
}
