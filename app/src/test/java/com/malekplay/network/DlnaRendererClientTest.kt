package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class DlnaRendererClientTest {

    @Test
    fun parsesValidUpnpClockTimesAndFractionalSeconds() {
        assertEquals(0L, parseDlnaClockTime("00:00:00"))
        assertEquals(3_723_000L, parseDlnaClockTime("01:02:03"))
        assertEquals(3_723_450L, parseDlnaClockTime("01:02:03.450"))
        assertEquals(3_723_456L, parseDlnaClockTime("01:02:03.4569"))
        assertEquals(90_000_000L, parseDlnaClockTime("25:00:00"))
    }

    @Test
    fun rejectsMissingMalformedAndUnsupportedClockValues() {
        listOf(
            null,
            "",
            "NOT_IMPLEMENTED",
            "00:00",
            "00:00:00:00",
            "x:00:00",
            "00:60:00",
            "00:00:60",
            "-01:00:00",
            "00:00:-1",
            "00:00:NaN"
        ).forEach { value ->
            assertNull("Expected invalid clock value: $value", parseDlnaClockTime(value))
        }
    }

    @Test
    fun rejectsClockValuesThatWouldOverflowMilliseconds() {
        assertNull(parseDlnaClockTime("999999999999999999999:00:00"))
        assertNull(parseDlnaClockTime("2562047788016:00:00"))
    }

    @Test
    fun endpointCacheIdentityChangesWhenDiscoveredHostOrDescriptionChanges() {
        val original = dlnaEndpointCacheKey(
            "uuid:living-room-tv",
            "192.168.1.20",
            "http://192.168.1.20:1400/root.xml"
        )
        assertEquals(
            original,
            dlnaEndpointCacheKey(
                "uuid:living-room-tv",
                "192.168.1.20",
                "http://192.168.1.20:1400/root.xml"
            )
        )
        assertNotEquals(
            original,
            dlnaEndpointCacheKey(
                "uuid:living-room-tv",
                "192.168.1.21",
                "http://192.168.1.21:1400/root.xml"
            )
        )
        assertNotEquals(
            original,
            dlnaEndpointCacheKey(
                "uuid:living-room-tv",
                "192.168.1.20",
                "http://192.168.1.20:1500/root.xml"
            )
        )
    }
    @Test
    fun mapsMimeTypesToSpecificUpnpClasses() {
        assertEquals("object.item.videoItem", dlnaUpnpClassForMime("video/mp4"))
        assertEquals("object.item.audioItem.musicTrack", dlnaUpnpClassForMime("audio/mpeg"))
        assertEquals("object.item.imageItem.photo", dlnaUpnpClassForMime("image/jpeg"))
        assertEquals("object.item.audioItem.musicTrack", dlnaUpnpClassForMime("AUDIO/FLAC; charset=binary"))
        assertEquals("object.item", dlnaUpnpClassForMime("application/octet-stream"))
    }

    @Test
    fun didlMetadataEscapesUntrustedTextAndUsesTheMediaClass() {
        val metadata = buildDlnaDidlMetadata(
            title = "Rock & <Jazz>",
            url = "http://192.168.1.5/music?a=1&b=2",
            mime = "audio/flac; charset=binary"
        )

        assertTrue(metadata.contains("<dc:title>Rock &amp; &lt;Jazz&gt;</dc:title>"))
        assertTrue(metadata.contains("<upnp:class>object.item.audioItem.musicTrack</upnp:class>"))
        assertTrue(metadata.contains("http-get:*:audio/flac:*"))
        assertTrue(metadata.contains("music?a=1&amp;b=2"))
        assertFalse(metadata.contains("object.item.videoItem"))
    }

    @Test
    fun boundedReaderAcceptsExactlyTheLimit() {
        val bytes = "12345".toByteArray()
        assertEquals(5, readBounded(ByteArrayInputStream(bytes), 5, "too large").size)
    }

    @Test
    fun boundedReaderRejectsOversizedBodyBeforeBufferingItCompletely() {
        val input = ByteArrayInputStream("123456789".toByteArray())
        val failure = runCatching {
            readBounded(input, 5, "response too large")
        }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals("response too large", failure?.message)
    }

}
