package com.malekplay.core.ui.theme

import androidx.compose.ui.graphics.Color

/** MalekPlay neon-amethyst cinematic palette. */
object MalekPlayColors {
    val NeonPurple = Color(0xFFA855F7)
    val Amethyst = NeonPurple
    val PurpleBright = Color(0xFFC084FC)
    val PurpleDeep = Color(0xFF7E22CE)
    val PurpleDark = Color(0xFF3B0764)

    val Emerald = Color(0xFF00D6A0)
    val Sapphire = Color(0xFF4F8CFF)
    val Ruby = Color(0xFFFF4D78)
    val Amber = Color(0xFFFFB84D)
    val Teal = Color(0xFF20D5D2)
    val Coral = Color(0xFFFF8066)
    val Lime = Color(0xFFA3E635)

    val Background = Color(0xFF0D0D12)
    val BackgroundElevated = Color(0xFF111019)
    val Surface = Color(0xFF15131D)
    val SurfaceVariant = Color(0xFF1D1928)
    val SurfaceElevated = Color(0xFF252033)

    val TextPrimary = Color(0xFFF8F5FF)
    val TextSecondary = Color(0xFFB9B1C8)
    val TextMuted = Color(0xFF81788F)

    val Border = Color(0xFF332642)
    val BorderStrong = Color(0xFF604080)
    val NeonGlow = Color(0x66A855F7)
    val Glass = Color(0xB315111F)

    val Accents = listOf(
        "amethyst" to Amethyst,
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
}

/** OLED surfaces remain pure black while preserving the purple accent. */
object AmoledColors {
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF08070B)
    val SurfaceVariant = Color(0xFF100D16)
    val SurfaceContainer = Color(0xFF100D16)
    val SurfaceContainerHigh = Color(0xFF1B1424)
    val Outline = Color(0xFF49335F)
}

/** Video-player chrome is intentionally dark in every application theme. */
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
