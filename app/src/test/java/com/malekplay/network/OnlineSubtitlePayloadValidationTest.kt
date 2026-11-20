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
        val ass = "[Script Info]\nTitle: test\nScriptType: v4.00+\n\n[V4+ Styles]\nFormat: Name, Fontname\n\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello".toByteArray()
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
    @Test
    fun providerCacheTokensDoNotReuseJavaHashCollisions() {
        // "Aa" and "BB" intentionally share String.hashCode() but must not share files.
        assertTrue("Aa".hashCode() == "BB".hashCode())
        assertFalse(subtitleCacheToken("Aa") == subtitleCacheToken("BB"))
        assertTrue(subtitleCacheToken("provider-id").matches(Regex("[a-f0-9]{64}")))
    }


    @Test
    fun acceptsShortWebVttAndSingleDigitHourSrtTimestamps() {
        val shortVtt = "WEBVTT\n\n00:01.250 --> 00:03.500\nHello".toByteArray()
        val oneDigitHourSrt = "1\n1:02:03,000 --> 1:02:05,000\nHello".toByteArray()
        assertTrue(isValidSubtitlePayload(shortVtt, "vtt"))
        assertTrue(isValidSubtitlePayload(oneDigitHourSrt, "srt"))
    }


    @Test
    fun acceptsUtf16EncodedSrtPayloads() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\nمرحبا"
        assertTrue(isValidSubtitlePayload(text.toByteArray(Charsets.UTF_16LE), "srt"))
        assertTrue(isValidSubtitlePayload(text.toByteArray(Charsets.UTF_16BE), "srt"))
    }

}
