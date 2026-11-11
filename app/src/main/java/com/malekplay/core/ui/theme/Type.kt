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
 * Applies the selected global text palette to every Material typography role.
 * The Aurora metallic brush remains the default only when no solid palette is selected.
 */
fun malekPlayTypography(isDark: Boolean, textColor: Color? = null): Typography {
    val brush = MalekPlayGradients.textBrush(isDark)
    // A selected text palette must replace the default metallic brush;
    // otherwise TextStyle.brush takes precedence over TextStyle.color.
    val applyTextPalette: (TextStyle) -> TextStyle = { style ->
        if (textColor == null) style.copy(brush = brush)
        else style.copy(brush = null as Brush?).copy(color = textColor)
    }
    return MalekPlayTypography.copy(
        displayLarge = applyTextPalette(MalekPlayTypography.displayLarge),
        displayMedium = applyTextPalette(MalekPlayTypography.displayMedium),
        displaySmall = applyTextPalette(MalekPlayTypography.displaySmall),
        headlineLarge = applyTextPalette(MalekPlayTypography.headlineLarge),
        headlineMedium = applyTextPalette(MalekPlayTypography.headlineMedium),
        headlineSmall = applyTextPalette(MalekPlayTypography.headlineSmall),
        titleLarge = applyTextPalette(MalekPlayTypography.titleLarge),
        titleMedium = applyTextPalette(MalekPlayTypography.titleMedium),
        titleSmall = applyTextPalette(MalekPlayTypography.titleSmall),
        bodyLarge = applyTextPalette(MalekPlayTypography.bodyLarge),
        bodyMedium = applyTextPalette(MalekPlayTypography.bodyMedium),
        bodySmall = applyTextPalette(MalekPlayTypography.bodySmall),
        // Material buttons provide their own semantic content color (onPrimary,
        // onSecondary, etc.). A fixed global label color overrides that contract and
        // can make button captions disappear against the accent-colored button surface.
        labelLarge = MalekPlayTypography.labelLarge.copy(
            color = Color.Unspecified
        ),
        labelMedium = applyTextPalette(MalekPlayTypography.labelMedium),
        labelSmall = applyTextPalette(MalekPlayTypography.labelSmall)
    )
}
