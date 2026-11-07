package com.malekplay.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Arabic-first typography. Line heights are relaxed slightly because Arabic
 * glyphs have taller ascenders/descenders than Latin text.
 */
val MalekPlayTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 52.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 40.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 33.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 29.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 26.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 23.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 23.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 21.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 19.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 17.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 15.sp
    )
)


/**
 * Applies one continuous metallic gold → orange → pink → violet brush to
 * every Material typography role. TextStyle's brush is retained even when a
 * screen supplies a semantic color, so all labels share the brand treatment.
 */
fun malekPlayTypography(isDark: Boolean): Typography {
    val brush = MalekPlayGradients.textBrush(isDark)
    return MalekPlayTypography.copy(
        displayLarge = MalekPlayTypography.displayLarge.copy(brush = brush),
        displayMedium = MalekPlayTypography.displayMedium.copy(brush = brush),
        displaySmall = MalekPlayTypography.displaySmall.copy(brush = brush),
        headlineLarge = MalekPlayTypography.headlineLarge.copy(brush = brush),
        headlineMedium = MalekPlayTypography.headlineMedium.copy(brush = brush),
        headlineSmall = MalekPlayTypography.headlineSmall.copy(brush = brush),
        titleLarge = MalekPlayTypography.titleLarge.copy(brush = brush),
        titleMedium = MalekPlayTypography.titleMedium.copy(brush = brush),
        titleSmall = MalekPlayTypography.titleSmall.copy(brush = brush),
        bodyLarge = MalekPlayTypography.bodyLarge.copy(brush = brush),
        bodyMedium = MalekPlayTypography.bodyMedium.copy(brush = brush),
        bodySmall = MalekPlayTypography.bodySmall.copy(brush = brush),
        labelLarge = MalekPlayTypography.labelLarge.copy(brush = brush),
        labelMedium = MalekPlayTypography.labelMedium.copy(brush = brush),
        labelSmall = MalekPlayTypography.labelSmall.copy(brush = brush)
    )
}
