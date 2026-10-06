package com.swpp.licenseplanner.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.swpp.licenseplanner.R

val PlexSansKr = FontFamily(
    Font(R.font.ibm_plex_sans_kr_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_sans_kr_medium, FontWeight.Medium),
    Font(R.font.ibm_plex_sans_kr_semibold, FontWeight.SemiBold),
)

val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

private fun sans(size: Int, weight: FontWeight, lineHeight: Double? = null) = TextStyle(
    fontFamily = PlexSansKr,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight?.em ?: TextStyle.Default.lineHeight,
)

/** Figma sizes: 24 heading, 19 question, 17 card title, 15/16 body and buttons, 12–14 labels. */
val AppTypography = Typography(
    headlineSmall = sans(24, FontWeight.SemiBold, 1.3),
    titleLarge = sans(19, FontWeight.SemiBold, 1.45),
    titleMedium = sans(17, FontWeight.SemiBold),
    titleSmall = sans(15, FontWeight.SemiBold, 1.4),
    bodyLarge = sans(15, FontWeight.Normal, 1.5),
    bodyMedium = sans(14, FontWeight.Normal, 1.45),
    bodySmall = sans(13, FontWeight.Normal, 1.5),
    labelLarge = sans(16, FontWeight.SemiBold),
    labelMedium = sans(13, FontWeight.SemiBold),
    labelSmall = sans(12, FontWeight.Normal),
)

/** IBM Plex Mono for answer formulas, times, and numbers. */
object MonoStyles {
    val answer = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 16.sp)
    val number = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    val tag = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 11.sp)
}
