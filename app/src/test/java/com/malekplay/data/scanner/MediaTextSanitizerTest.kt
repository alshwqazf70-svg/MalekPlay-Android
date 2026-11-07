package com.malekplay.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTextSanitizerTest {

    @Test
    fun readableMetadataTitleTakesPriority() {
        assertEquals("عنوان الأغنية", chooseMediaDisplayName("عنوان الأغنية", "song.mp3"))
    }

    @Test
    fun corruptedMetadataFallsBackToReadableArabicFilename() {
        assertEquals("يا حبيبي", chooseMediaDisplayName("????????", "يا حبيبي.mp3"))
    }

    @Test
    fun corruptedFilenameDoesNotReplaceReadableMetadata() {
        assertEquals("Beautiful Song", chooseMediaDisplayName("Beautiful Song", "??????.mp3"))
    }

    @Test
    fun replacementCharacterIsTreatedAsCorruption() {
        assertEquals("track", chooseMediaDisplayName("Bad\uFFFDTitle", "track.mp3"))
    }

    @Test
    fun blankNamesUseUntitledFallback() {
        assertEquals("Untitled", chooseMediaDisplayName("", ""))
    }

    @Test
    fun genericMetadataTitleFallsBackToDescriptiveFilename() {
        assertEquals("يا حبيبي", chooseMediaDisplayName("Unknown", "يا حبيبي.mp3"))
        assertEquals("Live Concert", chooseMediaDisplayName("No Title", "Live Concert.mp4"))
    }

    @Test
    fun meaningfulTitleContainingUnknownIsPreserved() {
        assertEquals("Unknown Pleasures", chooseMediaDisplayName("Unknown Pleasures", "track.mp3"))
    }

    @Test
    fun ordinaryQuestionMarkIsNotAutomaticallyRejected() {
        assertEquals("Who?", chooseMediaDisplayName("Who?", "track.mp3"))
    }
}
