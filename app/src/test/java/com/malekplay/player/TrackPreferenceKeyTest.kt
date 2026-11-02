package com.malekplay.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackPreferenceKeyTest {
    @Test fun keyUsesStableFormatMetadataRatherThanTrackGroupIndex() {
        assertEquals(
            "aac\u001Feng\u001FEnglish\u001Faudio/mp4a-latm",
            TrackPreferenceKey.fromParts("aac", "eng", "English", "audio/mp4a-latm")
        )
    }

    @Test fun missingMetadataStillProducesDeterministicKey() {
        assertEquals("\u001Fara\u001F\u001Ftext/vtt", TrackPreferenceKey.fromParts(null, "ara", null, "text/vtt"))
    }
}
