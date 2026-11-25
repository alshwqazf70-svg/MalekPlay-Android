package com.malekplay.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.malekplay.translation.SrtSubtitleParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OnlineSubtitleEndToEndTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, "online-subtitles").deleteRecursively()
    }

    @Test
    fun parserAndRepositoryValidatorAgreeAcrossTimingFormatsAndInvalidInputs() {
        val validPayloads = listOf(
            "1\n00:00:01,000 --> 00:00:03,000\nHello\n" to "srt",
            "1\n\u200E00:00:01,5 --> 00:00:03,2500\u200F\nمرحباً\n" to "srt",
            "1\n00:01:02 --> 00:01:05\nNo millis\n" to "srt",
            "1\n00:00:01:24 --> 00:00:03:10\nColon millis\n" to "srt",
            "WEBVTT\n\n00:01.250 --> 00:03.500 align:start\nShort VTT\n" to "vtt",
            "[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.50,Default,,0,0,0,,ASS cue\n" to "ass"
        )
        for ((text, expectedExt) in validPayloads) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val repoDetected = detectSubtitleFormat(bytes, preferArabicLegacy = true)
            val parserDetected = SrtSubtitleParser.detectFormat(text)
            assertEquals("Mismatch for format on:\n$text", expectedExt, repoDetected)
            assertEquals("Parser vs repo mismatch on:\n$text", repoDetected, parserDetected)
            assertTrue(isValidSubtitlePayload(bytes, expectedExt, preferArabicLegacy = true))

            val preview = extractPreviewLines(bytes, expectedExt, maxCues = 3, preferArabicLegacy = true)
            assertTrue("Expected preview lines for:\n$text", preview.isNotEmpty())
        }

        val invalidPayloads = listOf(
            "Just plain text with an arrow --> nothing",
            "1\n00:00:01,000 --> not-a-timestamp\nBad cue\n",
            "<!DOCTYPE html><html><body>00:00:01,000 --> 00:00:02,000</body></html>",
            ""
        )
        for (text in invalidPayloads) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            assertNull("Repository should reject:\n$text", detectSubtitleFormat(bytes, preferArabicLegacy = true))
            assertFalse("Payload check should reject:\n$text", isValidSubtitlePayload(bytes, "srt", preferArabicLegacy = true))
        }
    }

    @Test
    fun extractSelectsBestValidSubtitleFromMultiLanguageAndNoisyZip() {
        val arabicWin1256 = (
            "1\r\n" +
                "\u200E00:00:02,000 --> 00:00:05,000\u200F\r\n" +
                "أهلاً بكم في العرض التجريبي\r\n"
            ).toByteArray(Charset.forName("windows-1256"))
        val englishBigger = buildString {
            append("1\n00:00:01,000 --> 00:00:04,000\nEnglish line one\n\n")
            repeat(20) { idx ->
                append("${idx + 2}\n00:00:${10 + idx},000 --> 00:00:${11 + idx},000\nExtra English dialogue $idx\n\n")
            }
        }.toByteArray(Charsets.UTF_8)

        val zipBytes = createZip(
            "__MACOSX/._movie.ar.srt" to "corrupted mac fork".toByteArray(),
            "empty.srt" to byteArrayOf(),
            "readme.txt" to "Downloaded from subtitle archive".toByteArray(),
            "Subtitles/Movie.S01E02.English.srt" to englishBigger,
            "Subtitles/Movie.S01E02.Arabic.srt" to arabicWin1256
        )

        val repo = createMockedRepository { _ -> error("No HTTP expected") }
        val tempDir = File(context.cacheDir, "zip-extract-test").apply {
            deleteRecursively()
            mkdirs()
        }

        val arabicTarget = repo.extract(
            bytes = zipBytes,
            dir = tempDir,
            id = 1L,
            outputPrefix = "ar-choice",
            preferredFormat = "srt",
            preferArabicLegacy = true,
            seasonNumber = 1,
            episodeNumber = 2
        )
        assertEquals("srt", arabicTarget.extension)
        val arabicLines = extractPreviewLines(arabicTarget.readBytes(), arabicTarget.extension, maxCues = 3, preferArabicLegacy = true)
        assertEquals(listOf("[00:00:02,000] أهلاً بكم في العرض التجريبي"), arabicLines)

        val englishTarget = repo.extract(
            bytes = zipBytes,
            dir = tempDir,
            id = 2L,
            outputPrefix = "en-choice",
            preferredFormat = "srt",
            preferArabicLegacy = false,
            seasonNumber = 1,
            episodeNumber = 2
        )
        assertEquals("srt", englishTarget.extension)
        val englishLines = extractPreviewLines(englishTarget.readBytes(), englishTarget.extension, maxCues = 2, preferArabicLegacy = false)
        assertEquals("[00:00:01,000] English line one", englishLines.first())
    }

    @Test
    fun previewRequiresValidTimingLineAndIgnoresTiminglessBlocks() {
        val mixedSrt = """
            Translator Note: Enjoy the movie!

            1
            00:00:01,250 --> 00:00:03,500
            <i>First spoken line</i>

            Sponsor banner without timestamps

            2
            00:00:04,000 --> 00:00:06,000
            Second spoken line
        """.trimIndent().toByteArray(Charsets.UTF_8)

        val lines = extractPreviewLines(mixedSrt, "srt", maxCues = 5)
        assertEquals(
            listOf(
                "[00:00:01,250] First spoken line",
                "[00:00:04,000] Second spoken line"
            ),
            lines
        )

        val timinglessOnly = "Hello world\n\nNo timestamps anywhere".toByteArray(Charsets.UTF_8)
        assertTrue(extractPreviewLines(timinglessOnly, "srt", maxCues = 5).isEmpty())
    }

    @Test
    fun subdlEndToEndHandlesZipAndGzipMismatchedExtensionAndPreviewsCachedFile() = runBlocking {
        val assPayload = """
            [Script Info]
            Title: Mismatched ASS

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:05.00,0:00:08.00,Default,,0,0,0,,{\i1}مرحباً من ملف ASS\Nالسطر الثاني
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val zipWithAss = createZip("Release.ass" to assPayload)

        val vttPayload = """
            WEBVTT

            00:01.000 --> 00:03.500
            First VTT line inside gzip
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val gzipVtt = gzip(vttPayload)

        val repo = createMockedRepository { request ->
            val url = request.url.toString()
            when {
                url.endsWith("/subtitles/mismatched-ass") ->
                    binaryResponse(request, zipWithAss, "application/zip")
                url.endsWith("/subtitles/gzip-vtt") ->
                    binaryResponse(request, gzipVtt, "application/x-gzip")
                else -> error("Unexpected URL: $url")
            }
        }

        // Provider claimed "srt", actual payload is ZIP containing ASS.
        val assSub = OnlineSubtitle(
            fileId = 0L,
            language = "ar",
            release = "Movie.2025",
            downloads = 100,
            format = "srt",
            sourceId = "/subtitles/mismatched-ass",
            provider = "subdl"
        )
        val assUri = repo.download(assSub)
        val assFile = File(requireNotNull(assUri.path))
        assertTrue(assFile.exists())
        assertEquals("ass", assFile.extension)
        val assPreview = repo.previewSubtitleLines(assSub, maxCues = 3)
        assertEquals(listOf("[00:00:05,000] مرحباً من ملف ASS السطر الثاني"), assPreview)

        // Provider claimed "srt", actual payload is gzipped WebVTT.
        val vttSub = OnlineSubtitle(
            fileId = 0L,
            language = "en",
            release = "Movie.2025",
            downloads = 80,
            format = "srt",
            sourceId = "/subtitles/gzip-vtt",
            provider = "subdl"
        )
        val vttUri = repo.download(vttSub)
        val vttFile = File(requireNotNull(vttUri.path))
        assertTrue(vttFile.exists())
        assertEquals("vtt", vttFile.extension)
        val vttPreview = repo.previewSubtitleLines(vttSub, maxCues = 3)
        assertEquals(listOf("[00:01,000] First VTT line inside gzip"), vttPreview)
    }

    @Test
    fun subf2mEndToEndFetchesDetailPageDownloadsZipAndPreviewsArabicText() = runBlocking {
        val arabicSrtWin1256 = (
            "1\r\n" +
                "00:00:03,000 --> 00:00:06,500\r\n" +
                "Just a moment... ترجمة تجريبية عربية\r\n"
            ).toByteArray(Charset.forName("windows-1256"))
        val zipBytes = createZip(
            "__MACOSX/._bad.srt" to "junk".toByteArray(),
            "Arabic.srt" to arabicSrtWin1256
        )
        val detailHtml = """
            <html><body>
              <a id="downloadButton" href="https://isubcdn.com/download/999111">Download</a>
            </body></html>
        """.trimIndent()

        val repo = createMockedRepository { request ->
            val url = request.url.toString()
            when (url) {
                "https://subf2m.co/subtitles/test-movie/arabic/999111" ->
                    textResponse(request, detailHtml, "text/html")
                "https://isubcdn.com/download/999111" ->
                    binaryResponse(request, zipBytes, "application/zip")
                else -> error("Unexpected SubF2M URL: $url")
            }
        }

        val sub = OnlineSubtitle(
            fileId = 0L,
            language = "ar",
            release = "Test.Movie.2025",
            downloads = 50,
            format = "srt",
            sourceId = "ar:999111",
            provider = "subf2m",
            downloadUrl = "https://subf2m.co/subtitles/test-movie/arabic/999111"
        )
        val preview = repo.previewSubtitleLines(sub, maxCues = 3)
        assertEquals(listOf("[00:00:03,000] Just a moment... ترجمة تجريبية عربية"), preview)
    }

    @Test
    fun publicProviderRejectsBrokenTiminglessFileOnDownloadAndPreview() = runBlocking {
        val repo = createMockedRepository { request ->
            val url = request.url.toString()
            when (url) {
                "https://subtitles.website/strapi/api/search/download/sub-123" ->
                    textResponse(request, """{"download_url":"/strapi/uploads/broken.srt"}""", "application/json")
                "https://subtitles.website/strapi/uploads/broken.srt" ->
                    textResponse(request, "This is not a valid subtitle file at all", "text/plain")
                else -> error("Unexpected public URL: $url")
            }
        }

        val sub = OnlineSubtitle(
            fileId = 0L,
            language = "en",
            release = "Broken.Release",
            downloads = 10,
            format = "srt",
            sourceId = "sub-123",
            provider = "subtitleSearchEngine"
        )
        val err = runCatching { repo.previewSubtitleLines(sub) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(
            "Expected timing validation error, got: ${err?.message}",
            err?.message?.contains("أسطر توقيت صالحة") == true
        )
    }

    private fun createMockedRepository(handler: (okhttp3.Request) -> Response): OnlineSubtitleRepository {
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(Interceptor { chain -> handler(chain.request()) })
            .build()
        return OnlineSubtitleRepository(
            context = context,
            vault = NetworkCredentialVault(context),
            client = client,
            subf2mProvider = Subf2mSubtitleProvider(client)
        )
    }

    private fun textResponse(request: okhttp3.Request, body: String, mime: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", mime)
            .body(body.toResponseBody(mime.toMediaType()))
            .build()

    private fun binaryResponse(request: okhttp3.Request, bytes: ByteArray, mime: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", mime)
            .body(bytes.toResponseBody(mime.toMediaType()))
            .build()

    private fun createZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
}
