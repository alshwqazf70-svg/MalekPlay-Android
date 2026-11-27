package com.malekplay.network

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.malekplay.translation.SrtSubtitleParser
import com.malekplay.translation.SubtitleTranslationEngine
import com.malekplay.translation.SubtitleTranslationPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SubtitleEngineNonRegressionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.cacheDir, "online-subtitles").deleteRecursively()
        context.cacheDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("malekplay-ar-") }
            ?.forEach { it.delete() }
    }

    @Test
    fun searchAutomaticCleansQueryCombinesResultsAndRanksArabicFirst() {
        val repo = createMockedRepository { request ->
            val url = request.url.toString()
            when {
                url.startsWith("https://subtitles.website/") -> {
                    val json = """
                        {
                          "data": [
                            {
                              "id": "pub-en-1",
                              "language": "en",
                              "release": "Dune.Part.Two.2024.1080p.WEB",
                              "downloads": 950,
                              "format": "srt"
                            },
                            {
                              "id": "pub-ar-1",
                              "language": "ar",
                              "release": "Dune.Part.Two.2024.Arabic",
                              "downloads": 300,
                              "format": "srt"
                            }
                          ]
                        }
                    """.trimIndent()
                    textResponse(request, json, "application/json")
                }
                else -> Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(404)
                    .message("Not Found")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
        }

        val results = repo.searchAutomatic(
            title = "Dune.Part.Two.2024.1080p.BluRay.x264.mkv",
            language = "ar,en",
            season = null,
            episode = null
        )
        assertEquals(2, results.size)
        assertEquals("ar", SubtitleQueryCleaner.normalizeLanguage(results.first().language))
        assertEquals("en", SubtitleQueryCleaner.normalizeLanguage(results[1].language))
    }

    @Test
    fun downloadAndPreviewSupportSsaAssVttSrtZipAndArabicEncodings() = runBlocking {
        val ssaPayload = """
            [Script Info]
            Title: Classic SSA
            ScriptType: v4.00

            [Events]
            Format: Marked, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: Marked=0,0:00:02.00,0:00:05.00,Default,,0,0,0,,{\an8}مرحباً من SSA\Nسطر ثانٍ
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

        val vttPayload = (
            "\uFEFFWEBVTT\n\n" +
                "00:00:01.200 --> 00:00:03.400\n" +
                "<i>Hello VTT cue</i>\n"
            ).toByteArray(StandardCharsets.UTF_8)

        val arabicWin1256Srt = (
            "1\r\n" +
                "00:00:04,000 --> 00:00:07,000\r\n" +
                "تجربة ترميز ويندوز 1256 العربي\r\n"
            ).toByteArray(Charset.forName("windows-1256"))
        assertFalse("Test fixture itself must be representable in windows-1256", arabicWin1256Srt.contains('?'.code.toByte()))

        val utf16LeSrt = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + (
            "1\r\n" +
                "00:00:08,000 --> 00:00:10,000\r\n" +
                "ترجمة UTF-16LE عربية\r\n"
            ).toByteArray(StandardCharsets.UTF_16LE)

        val multiZip = createZip(
            "English.srt" to "1\n00:00:01,000 --> 00:00:02,000\nEnglish line\n".toByteArray(StandardCharsets.UTF_8),
            "Arabic_Release.srt" to arabicWin1256Srt
        )

        val repo = createMockedRepository { request ->
            when (request.url.toString()) {
                "https://dl.subdl.com/subtitle/ssa-test" -> binaryResponse(request, ssaPayload, "text/plain")
                "https://dl.subdl.com/subtitle/vtt-test" -> binaryResponse(request, vttPayload, "text/vtt")
                "https://dl.subdl.com/subtitle/zip-ar" -> binaryResponse(request, multiZip, "application/zip")
                "https://dl.subdl.com/subtitle/utf16-ar" -> binaryResponse(request, utf16LeSrt, "text/plain")
                else -> error("Unexpected URL: ${request.url}")
            }
        }

        val ssaSub = OnlineSubtitle(0L, "ar", "Show.SSA", 20, "ssa", "/subtitle/ssa-test", "subdl")
        val ssaFile = File(requireNotNull(repo.download(ssaSub).path))
        assertEquals("ssa", ssaFile.extension)
        assertEquals(listOf("[00:00:02,000] مرحباً من SSA سطر ثانٍ"), repo.previewSubtitleLines(ssaSub, 3))

        val vttSub = OnlineSubtitle(0L, "en", "Show.VTT", 30, "vtt", "/subtitle/vtt-test", "subdl")
        val vttFile = File(requireNotNull(repo.download(vttSub).path))
        assertEquals("vtt", vttFile.extension)
        assertEquals(listOf("[00:00:01,200] Hello VTT cue"), repo.previewSubtitleLines(vttSub, 3))

        val zipSub = OnlineSubtitle(0L, "ara", "Show.ZIP", 40, "srt", "/subtitle/zip-ar", "subdl")
        val zipFile = File(requireNotNull(repo.download(zipSub).path))
        assertEquals("srt", zipFile.extension)
        assertEquals(
            listOf("[00:00:04,000] تجربة ترميز ويندوز 1256 العربي"),
            repo.previewSubtitleLines(zipSub, 3)
        )

        val utf16Sub = OnlineSubtitle(0L, "ar", "Show.UTF16", 15, "srt", "/subtitle/utf16-ar", "subdl")
        assertEquals(
            listOf("[00:00:08,000] ترجمة UTF-16LE عربية"),
            repo.previewSubtitleLines(utf16Sub, 3)
        )
    }

    @Test
    fun repositoryRejectsHtmlAndEmptyPayloadsWithoutCrashing() = runBlocking {
        val repo = createMockedRepository { request ->
            when (request.url.toString()) {
                "https://dl.subdl.com/subtitle/cloudflare" ->
                    textResponse(request, "<!DOCTYPE html><html><title>Just a moment...</title></html>", "text/html")
                "https://dl.subdl.com/subtitle/empty" ->
                    binaryResponse(request, ByteArray(0), "application/octet-stream")
                else -> error("Unexpected URL: ${request.url}")
            }
        }

        val htmlError = runCatching {
            repo.download(OnlineSubtitle(0L, "en", "CF", 1, "srt", "/subtitle/cloudflare", "subdl"))
        }.exceptionOrNull()
        assertNotNull(htmlError)

        val emptyError = runCatching {
            repo.download(OnlineSubtitle(0L, "en", "Empty", 1, "srt", "/subtitle/empty", "subdl"))
        }.exceptionOrNull()
        assertNotNull(emptyError)
    }

    @Test
    fun parserAndTranslationPolicyPreserveTimingAssFormattingAndVttCues() {
        val assSource = """
            [Script Info]
            Title: Test ASS

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:01.00,0:00:03.50,Default,,0,0,0,,{\i1}Hello\Nworld{\i0}
        """.trimIndent()
        assertEquals("ass", SrtSubtitleParser.detectFormat(assSource))
        val assCues = SrtSubtitleParser.splitBlocks(assSource, "ass")
        val dialogueCue = assCues.single()
        assertTrue(dialogueCue.startsWith("1\n00:00:01,000 --> 00:00:03,500"))
        val cueText = dialogueCue.substringAfter("00:00:03,500\n")
        val (masked, tags) = SrtSubtitleParser.maskMarkup(cueText)
        val restored = SrtSubtitleParser.restoreMarkup(masked.replace("Hello", "مرحباً").replace("world", "بالعالم"), tags)
        assertTrue(restored.contains("""{\i1}"""))
        assertTrue(restored.contains("""\N"""))
        assertTrue(restored.contains("""{\i0}"""))

        val mergedTwoSpeakers = SubtitleTranslationPolicy.mergeWrappedDialogueLines("- First speaker\n- Second speaker")
        assertEquals("- First speaker\n- Second speaker", mergedTwoSpeakers)
    }

    @Test
    fun translationEngineReusesCachedResultValidatesInputsAndPropagatesCancellation() = runBlocking {
        val engine = SubtitleTranslationEngine(context)

        val sourceFile = File(context.cacheDir, "sample-en.srt")
        val srtBytes = "1\n00:00:01,000 --> 00:00:03,000\nHello world\n".toByteArray(StandardCharsets.UTF_8)
        sourceFile.writeBytes(srtBytes)
        val sourceUri = Uri.fromFile(sourceFile)

        // Pre-populate deterministic translation cache entry and verify cache hit without network/ML Kit download.
        val digest = MessageDigest.getInstance("SHA-256").run {
            update("en".toByteArray(StandardCharsets.UTF_8))
            update(byteArrayOf(0))
            digest(srtBytes).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
        }
        val cachedOutput = File(context.cacheDir, "malekplay-ar-$digest.srt")
        cachedOutput.writeText("\uFEFF1\n00:00:01,000 --> 00:00:03,000\nمرحباً بالعالم\n", StandardCharsets.UTF_8)

        var progressReports = emptyList<Pair<Int, Int>>()
        val translatedUri = engine.translateSrtToArabic(sourceUri, "en") { completed, total ->
            progressReports = progressReports + (completed to total)
        }
        assertEquals(Uri.fromFile(cachedOutput), translatedUri)
        assertEquals(listOf(1 to 1), progressReports)

        // Rejects Arabic source language.
        val arabicSourceError = runCatching {
            engine.translateSrtToArabic(sourceUri, "ar")
        }.exceptionOrNull()
        assertTrue(arabicSourceError is IllegalArgumentException)

        // Rejects unsupported source language.
        val unsupportedLangError = runCatching {
            engine.translateSrtToArabic(sourceUri, "zz")
        }.exceptionOrNull()
        assertTrue(unsupportedLangError is IllegalArgumentException)

        // Rejects invalid/corrupt empty subtitle payload.
        val brokenFile = File(context.cacheDir, "broken.srt").apply { writeText("not a subtitle file") }
        val brokenError = runCatching {
            engine.translateSrtToArabic(Uri.fromFile(brokenFile), "en")
        }.exceptionOrNull()
        assertTrue(brokenError is IllegalStateException)

        // Cancellation propagates cleanly without wrapping CancellationException.
        val cancelledJob: Job = launch {
            kotlinx.coroutines.delay(500)
            engine.translateSrtToArabic(sourceUri, "en")
        }
        cancelledJob.cancelAndJoin()
        assertTrue(cancelledJob.isCancelled)
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
}
