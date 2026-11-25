package com.malekplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun subtitleDownloadRedirectPolicyRejectsDowngradesAndUntrustedHosts() {
        assertTrue(isSafeSubtitleDownloadUrl("https://subtitles.website/file.srt", "subtitles.website"))
        assertTrue(isSafeSubtitleDownloadUrl("https://cdn.subtitles.website/file.srt", "subtitles.website"))
        assertFalse(isSafeSubtitleDownloadUrl("https://evil-subtitles.website/file.srt", "subtitles.website"))
        assertFalse(isSafeSubtitleDownloadUrl("http://subtitles.website/file.srt", "subtitles.website"))
        assertFalse(isSafeSubtitleDownloadUrl("https://user@subtitles.website/file.srt", "subtitles.website"))
        assertFalse(isSafeSubtitleDownloadUrl("https://subtitles.website:8443/file.srt", "subtitles.website"))
        assertTrue(isSafeSubtitleDownloadUrl("https://cdn.provider.example/file.srt"))
        assertFalse(isSafeSubtitleDownloadUrl("http://cdn.provider.example/file.srt"))
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

    @Test
    fun rejectsCloudflareHtmlEvenWhenContainingHtmlCommentsAndDetectsContentType() {
        val cloudflareHtml = "<!DOCTYPE html><html><!-- comment --><body>Just a moment...</body></html>"
            .toByteArray(Charsets.UTF_8)
        assertFalse(isValidSubtitlePayload(cloudflareHtml, "srt"))
        assertTrue(detectSubtitleFormat(cloudflareHtml) == null)
        assertTrue(isHtmlOrCloudflarePayload(cloudflareHtml, "text/html; charset=utf-8"))

        val error = runCatching {
            validateSubtitleDownloadResponse("text/html; charset=utf-8", cloudflareHtml, "SubDL")
        }.exceptionOrNull()
        assertTrue(error?.message?.contains("Cloudflare") == true)

        // Valid SubRip / text/plain / ZIP responses should pass response validation.
        val validSrt = "1\n00:00:01,000 --> 00:00:04,000\nHello".toByteArray(Charsets.UTF_8)
        validateSubtitleDownloadResponse("application/x-subrip", validSrt, "SubDL")
        validateSubtitleDownloadResponse("text/plain; charset=utf-8", validSrt, "SubDL")
    }

    @Test
    fun normalizesUtf16AndWindows1252ToUtf8WithBomForPlayback() {
        val rawText = "1\r\n00:00:01,000 --> 00:00:04,000\r\nCafé — مرحبا"
        val utf16LeWithBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            rawText.toByteArray(Charsets.UTF_16LE)
        val normalizedFromUtf16 = normalizeSubtitleForStorage(utf16LeWithBom, "srt")
        assertTrue(
            normalizedFromUtf16.size > 3 &&
                normalizedFromUtf16[0] == 0xEF.toByte() &&
                normalizedFromUtf16[1] == 0xBB.toByte() &&
                normalizedFromUtf16[2] == 0xBF.toByte()
        )
        assertTrue(isValidSubtitlePayload(normalizedFromUtf16, "srt"))
        val decoded = String(normalizedFromUtf16, 3, normalizedFromUtf16.size - 3, Charsets.UTF_8)
        assertTrue(decoded.contains("00:00:01,000 --> 00:00:04,000"))
        assertTrue(decoded.contains("Café — مرحبا"))

        val win1252Text = "1\n00:00:01,000 --> 00:00:04,000\nCafé — résumé"
        val win1252Bytes = win1252Text.toByteArray(java.nio.charset.Charset.forName("windows-1252"))
        val normalizedFromWin1252 = normalizeSubtitleForStorage(win1252Bytes, "srt", preferArabicLegacy = false)
        val decoded1252 = String(normalizedFromWin1252, 3, normalizedFromWin1252.size - 3, Charsets.UTF_8)
        assertTrue(decoded1252.contains("Café — résumé"))
    }

    @Test
    fun `accepts srt timings without spaces around arrow and rejects html fragment`() {
        val compactArrowSrt = "1\n00:00:01,000-->00:00:04,000\nمرحباً بكم\n".toByteArray(Charsets.UTF_8)
        assertEquals("srt", detectSubtitleFormat(compactArrowSrt, preferArabicLegacy = true))
        assertTrue(isValidSubtitlePayload(compactArrowSrt, "srt"))

        val htmlHeadFragment = "<head><title>Just a moment...</title></head><body><!-- 00:00:01,000 --> 00:00:02,000 --></body>"
            .toByteArray(Charsets.UTF_8)
        assertNull(detectSubtitleFormat(htmlHeadFragment, preferArabicLegacy = true))
        assertFalse(isValidSubtitlePayload(htmlHeadFragment, "srt"))
    }
}
