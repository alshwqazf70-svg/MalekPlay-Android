package com.malekplay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineSubtitlePayloadValidationTest {

    @Test
    fun acceptsValidSrtAndRejectsWrongExtension() {
        val bytes = "1\n00:00:01,000 --> 00:00:02,000\nHello".toByteArray()
        assertTrue(isValidSubtitlePayload(bytes, "srt"))
        assertFalse(isValidSubtitlePayload(bytes, "vtt"))
    }

    @Test
    fun acceptsVttAndAssSsaContent() {
        assertTrue(isValidSubtitlePayload("WEBVTT\n\n00:00:01.000 --> 00:00:02.000".toByteArray(), "vtt"))
        val ass = "[Script Info]\nTitle: test\n[Events]".toByteArray()
        assertTrue(isValidSubtitlePayload(ass, "ass"))
        assertTrue(isValidSubtitlePayload(ass, "ssa"))
    }

    @Test
    fun rejectsEmptyUnknownAndOversizedPayloads() {
        assertFalse(isValidSubtitlePayload(byteArrayOf(), "srt"))
        assertFalse(isValidSubtitlePayload("not a subtitle".toByteArray(), "srt"))
        assertFalse(isValidSubtitlePayload("WEBVTT".toByteArray(), "exe"))
        assertFalse(isValidSubtitlePayload(ByteArray(8 * 1024 * 1024 + 1), "srt"))
    }
}
