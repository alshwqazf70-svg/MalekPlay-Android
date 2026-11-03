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

/** Builds the scheme for one accent seed. */
private fun lightScheme(seed: Color): ColorScheme = lightColorScheme(
    primary = seed,
    onPrimary = Color.White,
    primaryContainer = seed.copy(alpha = 0.16f),
    onPrimaryContainer = seed,
    secondary = seed,
    onSecondary = Color.White,
    secondaryContainer = seed.copy(alpha = 0.12f),
    onSecondaryContainer = seed,
    tertiary = seed,
    background = Color(0xFFF6FAF8),
    onBackground = Color(0xFF101A17),
    surface = Color.White,
    onSurface = Color(0xFF101A17),
    surfaceVariant = Color(0xFFE7EFEC),
    onSurfaceVariant = Color(0xFF46554F),
    surfaceContainer = Color(0xFFF0F5F3),
    surfaceContainerHigh = Color(0xFFE9F0ED),
    outline = Color(0xFFC2D0CA),
    outlineVariant = Color(0xFFDCE6E2)
)

private fun darkScheme(seed: Color): ColorScheme = darkColorScheme(
    primary = seed,
    onPrimary = Color(0xFF160A24),
    primaryContainer = seed.copy(alpha = 0.20f),
    onPrimaryContainer = seed,
    secondary = seed,
    onSecondary = Color(0xFF160A24),
    secondaryContainer = seed.copy(alpha = 0.18f),
    onSecondaryContainer = seed,
    tertiary = seed,
    background = Color(0xFF08060D),
    onBackground = Color(0xFFF1EAFB),
    surface = Color(0xFF0D0A14),
    onSurface = Color(0xFFF1EAFB),
    surfaceVariant = Color(0xFF1A1423),
    onSurfaceVariant = Color(0xFFBDB0CF),
    surfaceContainer = Color(0xFF120D1B),
    surfaceContainerHigh = Color(0xFF1B1427),
    outline = Color(0xFF4A365F),
    outlineVariant = Color(0xFF30233F)
)

/** True black variant: same hues as dark but with OLED-friendly surfaces. */
private fun amoledScheme(seed: Color): ColorScheme {
    val base = darkScheme(seed)
    return base.copy(
        background = AmoledColors.Background,
        onBackground = Color(0xFFF1EAFB),
        surface = AmoledColors.Surface,
        onSurface = Color(0xFFF1EAFB),
        surfaceVariant = AmoledColors.SurfaceVariant,
        onSurfaceVariant = Color(0xFFBDB0CF),
        surfaceContainer = AmoledColors.SurfaceContainer,
        surfaceContainerHigh = AmoledColors.SurfaceContainerHigh,
        surfaceContainerLow = Color(0xFF05030A),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerHighest = Color(0xFF21172F),
        surfaceBright = Color(0xFF241A32),
        surfaceDim = Color(0xFF000000),
        outline = AmoledColors.Outline,
        outlineVariant = Color(0xFF241A32)
    )
}

@Composable
fun MalekPlayTheme(
    mode: AppThemeMode = AppThemeMode.SYSTEM,
    accent: String = "amethyst",
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.AMOLED -> true
        AppThemeMode.SYSTEM -> systemDark
    }
    val seed = MalekPlayColors.accentFor(accent)

    val colorScheme: ColorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        when {
            mode == AppThemeMode.AMOLED -> amoledScheme(seed)
            dark -> dynamicDarkColorScheme(context)
            else -> dynamicLightColorScheme(context)
        }
    } else {
        when (mode) {
            AppThemeMode.AMOLED -> amoledScheme(seed)
            AppThemeMode.LIGHT -> lightScheme(seed)
            else -> darkScheme(seed)
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MalekPlayTypography,
        content = content
    )
}

/** Convenience accessor for the player overlay, which always uses dark chrome. */
val isAmoled: Boolean
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.background == Color.Black
