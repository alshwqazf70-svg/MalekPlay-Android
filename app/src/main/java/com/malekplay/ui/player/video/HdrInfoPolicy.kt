package com.malekplay.ui.player.video

/**
 * Describes HDR metadata without processing video frames or changing the decoder.
 * Display HDR type values follow android.view.Display.HdrCapabilities.
 */
internal object HdrInfoPolicy {
    fun displayCapabilities(types: IntArray?): String {
        val labels = types.orEmpty().mapNotNull { type ->
            when (type) {
                1 -> "Dolby Vision"
                2 -> "HDR10"
                3 -> "HLG"
                4 -> "HDR10+"
                else -> null
            }
        }.distinct()
        return labels.takeIf { it.isNotEmpty() }?.joinToString("، ")
            ?: "لا توجد قدرة HDR معلنة"
    }

    fun streamFormat(codecs: String?, colorTransfer: Int?): String {
        val codec = codecs.orEmpty().lowercase()
        if (codec.contains("dvhe") || codec.contains("dvh1") ||
            codec.contains("dvav") || codec.contains("dva1")
        ) return "Dolby Vision"
        return when (colorTransfer) {
            6 -> "HDR10 / PQ"
            7 -> "HLG"
            else -> "SDR أو غير محدد"
        }
    }
}
