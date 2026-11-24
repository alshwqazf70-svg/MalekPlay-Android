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

private fun lightScheme(seed: Color, secondarySeed: Color, tertiarySeed: Color): ColorScheme {
    val primary = tintedSurface(seed, Color(0xFF1A1026), 0.18f)
    val primaryContainer = tintedSurface(Color.White, seed, 0.16f)
    val secondaryContainer = tintedSurface(Color.White, secondarySeed, 0.15f)
    return lightColorScheme(
        primary = primary,
        onPrimary = contrastingOnColor(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = tintedSurface(seed, Color.Black, 0.58f),
        secondary = tintedSurface(secondarySeed, Color(0xFF1A1026), 0.16f),
        onSecondary = contrastingOnColor(secondarySeed),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = tintedSurface(secondarySeed, Color.Black, 0.58f),
        tertiary = tintedSurface(tertiarySeed, Color(0xFF0A1F24), 0.16f),
        onTertiary = contrastingOnColor(tertiarySeed),
        background = MalekPlayColors.LightBackground,
        onBackground = MalekPlayColors.LightTextPrimary,
        surface = MalekPlayColors.LightSurface,
        onSurface = MalekPlayColors.LightTextPrimary,
        surfaceVariant = MalekPlayColors.LightSurfaceVariant,
        onSurfaceVariant = MalekPlayColors.LightTextSecondary,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF4F0FA),
        surfaceContainer = Color(0xFFEEE8F7),
        surfaceContainerHigh = Color(0xFFE6DDF4),
        surfaceContainerHighest = Color(0xFFDDD2EE),
        outline = tintedSurface(Color(0xFFB5A5C9), seed, 0.25f),
        outlineVariant = Color(0xFFDCD1EC)
    )
}

private fun darkScheme(seed: Color, secondarySeed: Color, tertiarySeed: Color): ColorScheme {
    val primaryContainer = tintedSurface(MalekPlayColors.Surface, seed, 0.26f)
    val secondaryContainer = tintedSurface(MalekPlayColors.SurfaceVariant, secondarySeed, 0.24f)
    val surfaceContainerHigh = tintedSurface(MalekPlayColors.SurfaceElevated, seed, 0.08f)
    val surfaceContainerHighest = tintedSurface(Color(0xFF2D253E), seed, 0.12f)
    return darkColorScheme(
        primary = seed,
        onPrimary = contrastingOnColor(seed),
        primaryContainer = primaryContainer,
        onPrimaryContainer = tintedSurface(seed, Color.White, 0.72f),
        secondary = secondarySeed,
        onSecondary = contrastingOnColor(secondarySeed),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = tintedSurface(secondarySeed, Color.White, 0.72f),
        tertiary = tertiarySeed,
        onTertiary = contrastingOnColor(tertiarySeed),
        background = MalekPlayColors.Background,
        onBackground = MalekPlayColors.TextPrimary,
        surface = MalekPlayColors.Surface,
        onSurface = MalekPlayColors.TextPrimary,
        surfaceVariant = MalekPlayColors.SurfaceVariant,
        onSurfaceVariant = MalekPlayColors.TextSecondary,
        surfaceContainerLowest = Color(0xFF08070C),
        surfaceContainerLow = Color(0xFF100E17),
        surfaceContainer = MalekPlayColors.Surface,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceBright = surfaceContainerHighest,
        surfaceDim = MalekPlayColors.Background,
        outline = tintedSurface(MalekPlayColors.BorderStrong, seed, 0.30f),
        outlineVariant = tintedSurface(MalekPlayColors.Border, seed, 0.16f)
    )
}

private fun amoledScheme(seed: Color, secondarySeed: Color, tertiarySeed: Color): ColorScheme =
    darkScheme(seed, secondarySeed, tertiarySeed).copy(
        background = AmoledColors.Background,
        onBackground = MalekPlayColors.TextPrimary,
        surface = AmoledColors.Surface,
        onSurface = MalekPlayColors.TextPrimary,
        surfaceVariant = AmoledColors.SurfaceVariant,
        onSurfaceVariant = MalekPlayColors.TextSecondary,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF050408),
        surfaceContainer = AmoledColors.SurfaceContainer,
        surfaceContainerHigh = tintedSurface(AmoledColors.SurfaceContainerHigh, seed, 0.08f),
        surfaceContainerHighest = tintedSurface(Color(0xFF231830), seed, 0.12f),
        surfaceBright = Color(0xFF281D34),
        surfaceDim = Color.Black,
        outline = tintedSurface(AmoledColors.Outline, seed, 0.28f),
        outlineVariant = Color(0xFF2B1F3A)
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
    val secondarySeed = MalekPlayColors.secondaryFor(accent)
    val tertiarySeed = MalekPlayColors.tertiaryFor(accent)

    val baseScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        when (effectiveMode) {
            AppThemeMode.AMOLED -> amoledScheme(seed, secondarySeed, tertiarySeed)
            AppThemeMode.DARK -> dynamicDarkColorScheme(context)
            AppThemeMode.LIGHT -> dynamicLightColorScheme(context)
            AppThemeMode.SYSTEM -> darkScheme(seed, secondarySeed, tertiarySeed)
        }
    } else {
        when (effectiveMode) {
            AppThemeMode.AMOLED -> amoledScheme(seed, secondarySeed, tertiarySeed)
            AppThemeMode.DARK -> darkScheme(seed, secondarySeed, tertiarySeed)
            AppThemeMode.LIGHT -> lightScheme(seed, secondarySeed, tertiarySeed)
            AppThemeMode.SYSTEM -> darkScheme(seed, secondarySeed, tertiarySeed)
        }
    }

    // Black text needs a light canvas; white text needs a dark canvas to remain readable.
    val canvasMode = when (textColorMode) {
        "black" -> AppThemeMode.LIGHT
        "white" -> if (effectiveMode == AppThemeMode.AMOLED) AppThemeMode.AMOLED else AppThemeMode.DARK
        else -> effectiveMode
    }
    val textBase = when (textColorMode) {
        "black" -> lightScheme(seed, secondarySeed, tertiarySeed)
        "white" -> if (effectiveMode == AppThemeMode.AMOLED) {
            amoledScheme(seed, secondarySeed, tertiarySeed)
        } else {
            darkScheme(seed, secondarySeed, tertiarySeed)
        }
        else -> baseScheme
    }
    val isLightCanvas = canvasMode == AppThemeMode.LIGHT

    // Keep the user's chosen accent color intact for buttons, indicators, and glows;
    // apply the text palette strictly to readable foreground text tokens.
    val chosenText: Color? = when (textColorMode) {
        "aurora" -> if (isLightCanvas) MalekPlayColors.LightTextPrimary else MalekPlayColors.TextPrimary
        "white" -> Color.White
        "black" -> Color(0xFF111111)
        "gold" -> if (isLightCanvas) Color(0xFF8C5A00) else Color(0xFFFFD873)
        "purple" -> if (isLightCanvas) Color(0xFF6B21A8) else Color(0xFFD8B4FE)
        "cyan" -> if (isLightCanvas) Color(0xFF066D7A) else Color(0xFF72E6F5)
        "mint" -> if (isLightCanvas) Color(0xFF136F4D) else Color(0xFF7AE8B8)
        "pink" -> if (isLightCanvas) Color(0xFFA81D5C) else Color(0xFFFF9EC0)
        else -> null
    }
    val secondaryText = when (textColorMode) {
        "aurora" -> if (isLightCanvas) MalekPlayColors.LightTextSecondary else MalekPlayColors.TextSecondary
        else -> chosenText?.copy(alpha = 0.78f)
    }

    val colorScheme = if (chosenText == null) {
        textBase
    } else {
        textBase.copy(
            onBackground = chosenText,
            onSurface = chosenText,
            onSurfaceVariant = secondaryText ?: chosenText.copy(alpha = 0.78f)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = malekPlayTypography(!isLightCanvas, chosenText),
        shapes = MalekPlayShapes,
        content = content
    )
}

/** True only for the pure-black OLED scheme. */
val isAmoled: Boolean
    @Composable
    @ReadOnlyComposable
    get() = MaterialTheme.colorScheme.surfaceDim == Color.Black
