package com.malekplay.core.ui.theme

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * Shared metallic brand gradient: the same continuous gold-to-magenta blend
 * is applied across each glyph, rather than assigning a separate color per letter.
 * Light mode uses deeper stops to preserve readable contrast on pale surfaces.
 */
object MalekPlayGradients {
    val DarkTextStops = listOf(
        Color(0xFFFFE27A),
        Color(0xFFFFA33D),
        Color(0xFFFF5FA2),
        Color(0xFFC34BFF)
    )
    val LightTextStops = listOf(
        Color(0xFF946200),
        Color(0xFFC15B12),
        Color(0xFFB92369),
        Color(0xFF7026A8)
    )

    fun textBrush(isDark: Boolean): Brush =
        Brush.horizontalGradient(if (isDark) DarkTextStops else LightTextStops)
}

/**
 * MalekPlay's semantic color tokens.
 *
 * Keep UI colors here rather than scattering hex values across screens. The
 * default accent is intentionally fixed to Neon Purple (#A855F7).
 */
object MalekPlayColors {
    // Brand / neon accents
    val NeonPurple = Color(0xFFA855F7)
    val Amethyst = NeonPurple
    val PurpleBright = Color(0xFFC084FC)
    val PurpleSoft = Color(0xFFE9D5FF)
    val PurpleDeep = Color(0xFF7E22CE)
    val PurpleDark = Color(0xFF3B0764)
    val NeonGlow = Color(0x66A855F7)

    // Supporting accents for media types and status
    val Emerald = Color(0xFF00D6A0)
    val Sapphire = Color(0xFF4F8CFF)
    val Ruby = Color(0xFFFF4D78)
    val Amber = Color(0xFFFFB84D)
    val Teal = Color(0xFF20D5D2)
    val Coral = Color(0xFFFF8066)
    val Lime = Color(0xFFA3E635)

    // Cinematic dark canvas and layered surfaces
    val Background = Color(0xFF0B0A10)
    val BackgroundElevated = Color(0xFF100E18)
    val Surface = Color(0xFF14121D)
    val SurfaceVariant = Color(0xFF1C1829)
    val SurfaceElevated = Color(0xFF241F35)
    val SurfaceGlass = Color(0xB8151121)

    // Light canvas and layered surfaces
    val LightBackground = Color(0xFFF7F4FC)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceVariant = Color(0xFFEDE6F5)
    val LightTextPrimary = Color(0xFF161122)
    val LightTextSecondary = Color(0xFF534868)

    // Typography and separators
    val TextPrimary = Color(0xFFF8F5FF)
    val TextSecondary = Color(0xFFBDB4CE)
    val TextMuted = Color(0xFF847A96)
    val Border = Color(0xFF332645)
    val BorderStrong = Color(0xFF634488)

    val Accents = listOf(
        "amethyst" to NeonPurple,
        "emerald" to Emerald,
        "sapphire" to Sapphire,
        "ruby" to Ruby,
        "amber" to Amber,
        "teal" to Teal,
        "coral" to Coral,
        "lime" to Lime
    )

    fun accentFor(id: String): Color =
        Accents.firstOrNull { it.first == id }?.second ?: NeonPurple

    fun secondaryFor(id: String): Color = when (id) {
        "emerald" -> Teal
        "sapphire" -> PurpleBright
        "ruby" -> Coral
        "amber" -> Coral
        "teal" -> Sapphire
        "coral" -> Amber
        "lime" -> Emerald
        else -> PurpleBright
    }

    fun tertiaryFor(id: String): Color = when (id) {
        "emerald" -> Amber
        "sapphire" -> Teal
        "ruby" -> Amber
        "amber" -> Ruby
        "teal" -> Emerald
        "coral" -> NeonPurple
        "lime" -> Teal
        else -> Teal
    }
}

/** Returns a crisp high-contrast foreground color (ink or pure white) for [background]. */
fun contrastingOnColor(background: Color): Color =
    if (background.luminance() > 0.44f) Color(0xFF110C1A) else Color.White

/** Mixes [accent] cleanly onto [canvas] for tinted glass or container surfaces. */
fun tintedSurface(canvas: Color, accent: Color, fraction: Float): Color =
    lerp(canvas, accent, fraction.coerceIn(0f, 1f))

/** OLED surfaces remain true black while preserving the MalekPlay accent. */
object AmoledColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF08070C)
    val SurfaceVariant = Color(0xFF110E19)
    val SurfaceContainer = Color(0xFF100D17)
    val SurfaceContainerHigh = Color(0xFF1B1527)
    val Outline = Color(0xFF4C3665)
}

/** Shared colors for the video player's controls and gesture overlays. */
object PlayerChrome {
    val Scrim = Color(0xCC000000)
    val Surface = Color(0xD916111F)
    val SurfaceStrong = Color(0xF21B1426)
    val OnSurface = Color.White
    val OnSurfaceMuted = Color(0xFFD5CDE0)
    val ProgressActive = MalekPlayColors.NeonPurple
    val ProgressGlow = Color(0x80A855F7)
    val ProgressInactive = Color(0x55FFFFFF)
    val Buffered = Color(0x80FFFFFF)
    val GestureOverlay = Color(0xD91A1225)
}
