package com.malekplay.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class AppThemeMode { LIGHT, DARK, AMOLED, SYSTEM }

private fun lightScheme(seed: Color): ColorScheme = lightColorScheme(
    primary = seed,
    onPrimary = Color.White,
    primaryContainer = seed.copy(alpha = 0.15f),
    onPrimaryContainer = Color(0xFF351052),
    secondary = MalekPlayColors.PurpleDeep,
    onSecondary = Color.White,
    secondaryContainer = MalekPlayColors.PurpleDeep.copy(alpha = 0.12f),
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
    primaryContainer = seed.copy(alpha = 0.22f),
    onPrimaryContainer = Color(0xFFE9D5FF),
    secondary = MalekPlayColors.PurpleBright,
    onSecondary = Color(0xFF210B35),
    secondaryContainer = MalekPlayColors.PurpleDeep.copy(alpha = 0.28f),
    onSecondaryContainer = Color(0xFFE9D5FF),
    tertiary = MalekPlayColors.Sapphire,
    onTertiary = Color.White,
    background = MalekPlayColors.Background,
    onBackground = MalekPlayColors.TextPrimary,
    surface = MalekPlayColors.Surface,
    onSurface = MalekPlayColors.TextPrimary,
    surfaceVariant = MalekPlayColors.SurfaceVariant,
    onSurfaceVariant = MalekPlayColors.TextSecondary,
    surfaceContainer = MalekPlayColors.Surface,
    surfaceContainerHigh = MalekPlayColors.SurfaceElevated,
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
    surfaceContainer = AmoledColors.SurfaceContainer,
    surfaceContainerHigh = AmoledColors.SurfaceContainerHigh,
    surfaceContainerLow = Color(0xFF050505),
    surfaceContainerLowest = Color.Black,
    surfaceContainerHighest = Color(0xFF24182F),
    surfaceBright = Color(0xFF281D34),
    surfaceDim = Color.Black,
    outline = AmoledColors.Outline,
    outlineVariant = Color(0xFF2D2039)
)

@Composable
fun MalekPlayTheme(
    mode: AppThemeMode = AppThemeMode.AMOLED,
    accent: String = "amethyst",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val effectiveMode = when (mode) {
        AppThemeMode.SYSTEM -> if (systemDark) AppThemeMode.DARK else AppThemeMode.LIGHT
        else -> mode
    }
    val seed = MalekPlayColors.accentFor(accent)

    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MalekPlayTypography,
        content = content
    )
}

/** True only for the pure-black OLED scheme. */
val isAmoled: Boolean
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceDim == Color.Black
