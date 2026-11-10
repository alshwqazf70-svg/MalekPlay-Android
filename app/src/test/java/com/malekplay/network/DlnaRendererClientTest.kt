package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
        assertNull(parseDlnaClockTime("2562047788:00:00"))
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
}
