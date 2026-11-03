package com.malekplay.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand palette. Emerald is the signature MalekPlay colour; the remaining
 * seeds let the user recolour the whole app from Settings.
 */
object MalekPlayColors {
    val Emerald = Color(0xFF00C897)
    val Sapphire = Color(0xFF3D7EFF)
    val Amethyst = Color(0xFF9B5CFF)
    val Ruby = Color(0xFFFF5470)
    val Amber = Color(0xFFFFB300)
    val Teal = Color(0xFF00BFA5)
    val Coral = Color(0xFFFF7A59)
    val Lime = Color(0xFF8BC34A)

    /** Accent choices offered in Settings, keyed by stable id. */
    val Accents = listOf(
        "emerald" to Emerald,
        "sapphire" to Sapphire,
        "amethyst" to Amethyst,
        "ruby" to Ruby,
        "amber" to Amber,
        "teal" to Teal,
        "coral" to Coral,
        "lime" to Lime
    )

    fun accentFor(id: String): Color = Accents.firstOrNull { it.first == id }?.second ?: Emerald
}

/** Pure black surfaces used by the AMOLED theme to save power on OLED panels. */
object AmoledColors {
    val Background = Color(0xFF05030A)
    val Surface = Color(0xFF08060F)
    val SurfaceVariant = Color(0xFF100B1A)
    val SurfaceContainer = Color(0xFF100B19)
    val SurfaceContainerHigh = Color(0xFF1A1028)
    val Outline = Color(0xFF3A2855)
}

/** Player overlay chrome — always dark regardless of the app theme. */
object PlayerChrome {
    val Scrim = Color(0xB3000000)
    val Surface = Color(0xCC1A1A1A)
    val SurfaceStrong = Color(0xE61A1A1A)
    val OnSurface = Color.White
    val ProgressActive = Color(0xFF9B5CFF)
    val ProgressInactive = Color(0x59FFFFFF)
    val Buffered = Color(0x80FFFFFF)
}
