package com.malekplay.player

import com.malekplay.ui.player.video.HdrInfoPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class HdrInfoPolicyTest {
    @Test
    fun displayCapabilitiesListsOnlyRecognizedTypes() {
        assertEquals("HDR10، HLG", HdrInfoPolicy.displayCapabilities(intArrayOf(2, 3, 99)))
    }

    @Test
    fun emptyDisplayCapabilitiesAreExplicit() {
        assertEquals("لا توجد قدرة HDR معلنة", HdrInfoPolicy.displayCapabilities(null))
        assertEquals("لا توجد قدرة HDR معلنة", HdrInfoPolicy.displayCapabilities(intArrayOf()))
    }

    @Test
    fun recognizesDolbyVisionFromCodecString() {
        assertEquals("Dolby Vision", HdrInfoPolicy.streamFormat("dvhe.08.06", 6))
    }

    @Test
    fun recognizesPqAndHlgTransfers() {
        assertEquals("HDR10 / PQ", HdrInfoPolicy.streamFormat("avc1", 6))
        assertEquals("HLG", HdrInfoPolicy.streamFormat("hev1", 7))
        assertEquals("SDR أو غير محدد", HdrInfoPolicy.streamFormat("avc1", null))
    }
}
