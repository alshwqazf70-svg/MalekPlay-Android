package com.malekplay.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

enum class AppThemeMode { LIGHT, DARK, AMOLED, SYSTEM }

/**
 * The application-wide shape language: soft cinema panels, rounded media
 * cards and compact controls. Screens can still override a shape intentionally.
 */
val MalekPlayShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

private fun lightScheme(seed: Color): ColorScheme = lightColorScheme(
    primary = seed,
    onPrimary = Color(0xFF210B35),
    primaryContainer = Color(0xFFF0DFFF),
    onPrimaryContainer = Color(0xFF351052),
    secondary = MalekPlayColors.PurpleDeep,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF1E4FF),
    onSecondaryContainer = Color(0xFF351052),
    tertiary = MalekPlayColors.Sapphire,
    onTertiary = Color.White,
    background = Color(0xFFF7F4FA),
    onBackground = Color(0xFF17121D),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF17121D),
    surfaceVariant = Color(0xFFECE5F2),
    onSurfaceVariant = Color(0xFF51465D),
    surfaceContainer = Color(0xFFF0EAF5),
    surfaceContainerHigh = Color(0xFFE8E0EF),
    outline = Color(0xFFBBAACB),
    outlineVariant = Color(0xFFDDD2E7)
)

private fun darkScheme(seed: Color): ColorScheme = darkColorScheme(
    primary = seed,
    onPrimary = Color(0xFF210B35),
    primaryContainer = Color(0xFF35134F),
    onPrimaryContainer = Color(0xFFE9D5FF),
    secondary = MalekPlayColors.PurpleBright,
    onSecondary = Color(0xFF210B35),
    secondaryContainer = Color(0xFF3B2452),
    onSecondaryContainer = Color(0xFFF1E5FF),
    tertiary = MalekPlayColors.Teal,
    onTertiary = Color(0xFF00201F),
    background = MalekPlayColors.Background,
    onBackground = MalekPlayColors.TextPrimary,
    surface = MalekPlayColors.Surface,
    onSurface = MalekPlayColors.TextPrimary,
    surfaceVariant = MalekPlayColors.SurfaceVariant,
    onSurfaceVariant = MalekPlayColors.TextSecondary,
    surfaceContainerLowest = Color(0xFF09080D),
    surfaceContainerLow = Color(0xFF100E16),
    surfaceContainer = MalekPlayColors.Surface,
    surfaceContainerHigh = MalekPlayColors.SurfaceElevated,
    surfaceContainerHighest = Color(0xFF30263D),
    surfaceBright = Color(0xFF30263D),
    surfaceDim = Color(0xFF0D0D12),
    outline = MalekPlayColors.BorderStrong,
    outlineVariant = MalekPlayColors.Border
)

private fun amoledScheme(seed: Color): ColorScheme = darkScheme(seed).copy(
    background = AmoledColors.Background,
    onBackground = MalekPlayColors.TextPrimary,
    surface = AmoledColors.Surface,
    onSurface = MalekPlayColors.TextPrimary,
    surfaceVariant = AmoledColors.SurfaceVariant,
    onSurfaceVariant = MalekPlayColors.TextSecondary,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainer = AmoledColors.SurfaceContainer,
    surfaceContainerHigh = AmoledColors.SurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFF24182F),
    surfaceBright = Color(0xFF281D34),
    surfaceDim = Color.Black,
    outline = AmoledColors.Outline,
    outlineVariant = Color(0xFF2D2039)
)

/**
 * Dark mode is the default and uses the MalekPlay Neon Purple palette.
 * Dynamic colors remain an explicit opt-in for users who prefer Android's
 * system palette; they are never enabled by default.
 */
@Composable
fun MalekPlayTheme(
    mode: AppThemeMode = AppThemeMode.DARK,
    accent: String = "amethyst",
    dynamicColor: Boolean = false,
    textColorMode: String = "aurora",
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val effectiveMode = when (mode) {
        AppThemeMode.SYSTEM -> if (systemDark) AppThemeMode.DARK else AppThemeMode.LIGHT
        else -> mode
    }
    val seed = MalekPlayColors.accentFor(accent)

    val baseScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        when (effectiveMode) {
            AppThemeMode.AMOLED -> amoledScheme(seed)
            AppThemeMode.DARK -> dynamicDarkColorScheme(context)
            AppThemeMode.LIGHT -> dynamicLightColorScheme(context)
            AppThemeMode.SYSTEM -> darkScheme(seed)
        }
    } else {
        when (effectiveMode) {
            AppThemeMode.AMOLED -> amoledScheme(seed)
            AppThemeMode.DARK -> darkScheme(seed)
            AppThemeMode.LIGHT -> lightScheme(seed)
            AppThemeMode.SYSTEM -> darkScheme(seed)
        }
    }

    // Black text needs a light canvas; white text needs a dark canvas to remain readable.
    val textBase = when (textColorMode) {
        "black" -> lightScheme(seed)
        "white" -> darkScheme(seed)
        else -> baseScheme
    }
    val chosenText = when (textColorMode) {
        "white" -> Color.White
        "black" -> Color(0xFF111111)
        "gold" -> Color(0xFFFFD166)
        "purple" -> MalekPlayColors.PurpleBright
        "cyan" -> Color(0xFF64DCEB)
        "mint" -> Color(0xFF69D5A8)
        "pink" -> Color(0xFFFF8FB1)
        else -> null
    }
    val colorScheme = if (chosenText == null) textBase else textBase.copy(
        onBackground = chosenText,
        onSurface = chosenText,
        onSurfaceVariant = chosenText.copy(alpha = 0.82f),
        onSurfaceVariant = chosenText.copy(alpha = 0.82f)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = malekPlayTypography(effectiveMode != AppThemeMode.LIGHT),
        shapes = MalekPlayShapes,
        content = content
    )
}

/** True only for the pure-black OLED scheme. */
val isAmoled: Boolean
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceDim == Color.Black
