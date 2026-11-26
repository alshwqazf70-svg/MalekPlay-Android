package com.malekplay.player.cast

import com.malekplay.network.NetworkDeviceKind
import com.malekplay.network.TvCastProtocol
import com.malekplay.network.classifyMdnsService
import com.malekplay.network.classifySsdpDevice
import com.malekplay.network.protocolsForMdnsService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebCastAndDiscoveryTests {

    @Test
    fun convertsSrtSubtitlesToValidWebVtt() {
        val srt = """
            1
            00:00:01,250 --> 00:00:04,500
            مرحبا بكم في <i>مالك بلاي</i>

            2
            00:00:05,000 --> 00:00:07,100
            السطر الثاني
        """.trimIndent()

        val vtt = CastMediaHttpServer.convertSubtitleToWebVtt(srt)
        assertTrue(vtt.startsWith("WEBVTT\n"))
        assertTrue(vtt.contains("00:00:01.250 --> 00:00:04.500"))
        assertTrue(vtt.contains("مرحبا بكم في <i>مالك بلاي</i>"))
        assertTrue(vtt.contains("00:00:05.000 --> 00:00:07.100"))
    }

    @Test
    fun convertsAssSubtitlesToWebVttAndStripsOverrideTags() {
        val ass = """
            [Script Info]
            Title: Demo

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.50,0:00:05.75,Default,,0,0,0,,{\pos(320,240)}Hello\NWorld
        """.trimIndent()

        val vtt = CastMediaHttpServer.convertSubtitleToWebVtt(ass)
        assertTrue(vtt.startsWith("WEBVTT\n"))
        assertTrue(vtt.contains("00:00:02.500 --> 00:00:05.750"))
        assertTrue(vtt.contains("Hello\nWorld"))
    }

    @Test
    fun infersMediaAndSubtitleMimeTypesFromFilename() {
        assertEquals("video/mp4", CastMediaHttpServer.inferMimeFromUriOrName(null, "movie.mp4"))
        assertEquals("video/x-matroska", CastMediaHttpServer.inferMimeFromUriOrName(null, "episode.mkv"))
        assertEquals("video/webm", CastMediaHttpServer.inferMimeFromUriOrName(null, "clip.webm"))
        assertEquals("audio/flac", CastMediaHttpServer.inferMimeFromUriOrName(null, "song.flac"))
        assertEquals("text/vtt", CastMediaHttpServer.inferMimeFromUriOrName(null, "sub.vtt"))
        assertEquals("application/x-subrip", CastMediaHttpServer.inferMimeFromUriOrName(null, "sub.srt"))
        assertEquals("text/x-ssa", CastMediaHttpServer.inferMimeFromUriOrName(null, "sub.ass"))
    }

    @Test
    fun decodesUtf8BomAndWindows1256ArabicSubtitles() {
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "مرحبا".toByteArray(Charsets.UTF_8)
        assertEquals("مرحبا", CastMediaHttpServer.decodeSubtitleBytes(utf8Bom))

        val cp1256Bytes = "ترجمة عربية".toByteArray(java.nio.charset.Charset.forName("windows-1256"))
        assertEquals("ترجمة عربية", CastMediaHttpServer.decodeSubtitleBytes(cp1256Bytes))
    }

    @Test
    fun classifiesExtendedTvMdnsServicesAndCastProtocols() {
        assertEquals(NetworkDeviceKind.MEDIA_RENDERER, classifyMdnsService("_roku-ecp._tcp.local"))
        assertEquals(NetworkDeviceKind.MEDIA_RENDERER, classifyMdnsService("_dial._tcp.local"))
        assertEquals(NetworkDeviceKind.MEDIA_RENDERER, classifyMdnsService("_dlna._tcp.local"))
        assertEquals(NetworkDeviceKind.WINDOWS_COMPUTER, classifyMdnsService("_workstation._tcp.local"))

        assertEquals(setOf(TvCastProtocol.ROKU_ECP), protocolsForMdnsService("_roku-ecp._tcp"))
        assertEquals(setOf(TvCastProtocol.CHROMECAST), protocolsForMdnsService("_googlecast._tcp"))
        assertEquals(setOf(TvCastProtocol.DIAL), protocolsForMdnsService("_dial._tcp"))
        assertEquals(setOf(TvCastProtocol.AIRPLAY), protocolsForMdnsService("_airplay._tcp"))
        assertEquals(setOf(TvCastProtocol.DLNA), protocolsForMdnsService("_dlna._tcp"))
    }

    @Test
    fun classifiesExtendedSsdpTargetsWhileKeepingRootDeviceGenericUntilXmlProbe() {
        assertEquals(NetworkDeviceKind.NETWORK_DEVICE, classifySsdpDevice("upnp:rootdevice", "uuid:abc"))
        assertEquals(NetworkDeviceKind.MEDIA_RENDERER, classifySsdpDevice("urn:schemas-upnp-org:device:MediaRenderer:1", "uuid:tv"))
        assertEquals(NetworkDeviceKind.MEDIA_RENDERER, classifySsdpDevice("urn:schemas-upnp-org:service:AVTransport:1", "uuid:tv"))
        assertEquals(NetworkDeviceKind.MEDIA_RENDERER, classifySsdpDevice("roku:ecp", "uuid:roku:123"))
        assertEquals(NetworkDeviceKind.MEDIA_SERVER, classifySsdpDevice("urn:schemas-upnp-org:device:MediaServer:1", "uuid:nas"))
    }
}
