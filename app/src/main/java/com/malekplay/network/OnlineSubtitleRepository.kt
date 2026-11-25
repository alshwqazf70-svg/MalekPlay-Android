package com.malekplay.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.malekplay.translation.SrtSubtitleParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class SubtitleHttpException(val statusCode: Int, message: String) : IOException(message)

/** Validates every subtitle download hop before the client opens its next connection. */
internal fun isSafeSubtitleDownloadUrl(rawUrl: String, allowedDomain: String? = null): Boolean =
    runCatching {
        val uri = java.net.URI(rawUrl)
        val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching false
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.rawUserInfo != null || uri.port !in listOf(-1, 443)
        ) {
            return@runCatching false
        }
        val domain = allowedDomain?.lowercase(Locale.ROOT) ?: return@runCatching true
        host == domain || host.endsWith(".$domain")
    }.getOrDefault(false)

data class OnlineSubtitle(
    val fileId: Long,
    val language: String,
    val release: String,
    val downloads: Int,
    val format: String,
    val sourceId: String = fileId.toString(),
    val provider: String = "opensubtitles",
    val downloadUrl: String? = null,
    val matchScore: Int? = null,
    /** Season/episode requested by the user; describes the search scope, not provider verification. */
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** Provider-confirmed episode metadata, separate from the requested search scope. */
    val providerSeasonNumber: Int? = null,
    val providerEpisodeNumber: Int? = null,
    val providerEpisodeEnd: Int? = null,
    val fullSeasonPack: Boolean = false
)

/** Collision-resistant, filesystem-safe cache token for untrusted provider identifiers. */
internal fun subtitleCacheToken(sourceId: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
        .digest(sourceId.toByteArray(Charsets.UTF_8))
    val hex = "0123456789abcdef"
    return buildString(digest.size * 2) {
        digest.forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
}

private val INVISIBLE_BIDI_MARKS_REGEX = Regex("""[\u200E\u200F\u202A-\u202E\u2066-\u2069\uFEFF\u00A0]""")
private val SUBTITLE_TIMESTAMP_REGEX = Regex(
    """(?m)^[ \t\u200E\u200F\u202A-\u202E\u2066-\u2069\uFEFF\u00A0]*(?:\d{1,3}:)?\d{1,2}:\d{2}(?:[,.]\d{1,4}|:\d{2})?[ \t\u200E\u200F]*-->[ \t\u200E\u200F]*(?:\d{1,3}:)?\d{1,2}:\d{2}(?:[,.]\d{1,4}|:\d{2})?(?:[ \t]+.*)?$"""
)
private val MICRODVD_LINE_REGEX = Regex("""^\s*\{(\d+)\}\{(\d+)\}(.+)$""")
private val SUBVIEWER_TIMING_REGEX = Regex("""^\s*(\d{1,2}:\d{2}:\d{2}[.,]\d{2}),(\d{1,2}:\d{2}:\d{2}[.,]\d{2})\s*$""")

private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

private fun cleanTimestampLine(line: String): String =
    INVISIBLE_BIDI_MARKS_REGEX.replace(line, " ").trim()

private fun isAssDialogueText(text: String): Boolean =
    text.contains("[Events]", ignoreCase = true) &&
        text.lineSequence().any { it.trimStart().startsWith("Dialogue:", ignoreCase = true) }

private fun formatSrtTimestampFromMillis(totalMillis: Long): String {
    val safe = totalMillis.coerceAtLeast(0L)
    val hours = safe / 3_600_000L
    val minutes = (safe / 60_000L) % 60L
    val seconds = (safe / 1_000L) % 60L
    val millis = safe % 1_000L
    return "%02d:%02d:%02d,%03d".format(Locale.ROOT, hours, minutes, seconds, millis)
}

/** Converts MicroDVD ({start}{end}text) or SubViewer (HH:MM:SS.cc,HH:MM:SS.cc) to SRT when matched. */
internal fun convertLegacySubToSrtIfApplicable(text: String): String? {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
    val microLines = lines.mapNotNull { line -> MICRODVD_LINE_REGEX.matchEntire(line)?.let { it to line } }
    if (microLines.size >= 2) {
        var fps = 23.976
        val firstMatch = microLines.first().first
        if (firstMatch.groupValues[1] == "1" && firstMatch.groupValues[2] == "1") {
            firstMatch.groupValues[3].trim().toDoubleOrNull()?.takeIf { it in 10.0..120.0 }?.let { fps = it }
        }
        val srtCues = ArrayList<String>(microLines.size)
        for ((match, _) in microLines) {
            val startFrame = match.groupValues[1].toLongOrNull() ?: continue
            val endFrame = match.groupValues[2].toLongOrNull() ?: continue
            if (startFrame == 1L && endFrame == 1L && match.groupValues[3].trim().toDoubleOrNull() != null) continue
            val startMs = ((startFrame * 1000.0) / fps).toLong()
            val endMs = ((endFrame * 1000.0) / fps).toLong().coerceAtLeast(startMs + 500L)
            val body = match.groupValues[3]
                .replace(Regex("""\{[^}]*\}"""), "")
                .replace("|", "\n")
                .trim()
            if (body.isBlank()) continue
            srtCues += "${srtCues.size + 1}\n${formatSrtTimestampFromMillis(startMs)} --> ${formatSrtTimestampFromMillis(endMs)}\n$body"
        }
        if (srtCues.isNotEmpty()) return srtCues.joinToString("\n\n") + "\n"
    }

    val subViewerIndices = lines.indices.filter { SUBVIEWER_TIMING_REGEX.matches(lines[it]) }
    if (subViewerIndices.size >= 2) {
        val srtCues = ArrayList<String>(subViewerIndices.size)
        for (pos in subViewerIndices.indices) {
            val lineIdx = subViewerIndices[pos]
            val match = SUBVIEWER_TIMING_REGEX.matchEntire(lines[lineIdx]) ?: continue
            val start = match.groupValues[1].replace('.', ',') + "0"
            val end = match.groupValues[2].replace('.', ',') + "0"
            val nextTimingIdx = subViewerIndices.getOrNull(pos + 1) ?: lines.size
            val body = lines.subList(lineIdx + 1, nextTimingIdx)
                .takeWhile { it.isNotBlank() && !it.trimStart().startsWith("[") }
                .joinToString("\n")
                .replace("[br]", "\n", ignoreCase = true)
                .trim()
            if (body.isBlank()) continue
            srtCues += "${srtCues.size + 1}\n$start --> $end\n$body"
        }
        if (srtCues.isNotEmpty()) return srtCues.joinToString("\n\n") + "\n"
    }
    return null
}

private fun unsupportedArchiveTypeMessage(bytes: ByteArray): String? {
    if (bytes.size >= 4 &&
        bytes[0] == 0x52.toByte() && bytes[1] == 0x61.toByte() &&
        bytes[2] == 0x72.toByte() && bytes[3] == 0x21.toByte()
    ) {
        return "ملف الترجمة مضغوط بصيغة RAR غير المدعومة؛ جرّب نتيجة أخرى بصيغة SRT أو ZIP."
    }
    if (bytes.size >= 6 &&
        bytes[0] == 0x37.toByte() && bytes[1] == 0x7A.toByte() &&
        bytes[2] == 0xBC.toByte() && bytes[3] == 0xAF.toByte() &&
        bytes[4] == 0x27.toByte() && bytes[5] == 0x1C.toByte()
    ) {
        return "ملف الترجمة مضغوط بصيغة 7z غير المدعومة؛ جرّب نتيجة أخرى بصيغة SRT أو ZIP."
    }
    return null
}

/**
 * Decodes subtitle bytes with explicit BOM handling (UTF-8, UTF-16LE/BE, UTF-32LE/BE),
 * BOM-less UTF-16 / strict UTF-8 detection, and Windows-1256 vs Windows-1252 disambiguation.
 */
internal fun decodeSubtitleBytes(bytes: ByteArray, preferArabicLegacy: Boolean = false): String {
    require(bytes.isNotEmpty()) { "ملف الترجمة فارغ" }
    // Handle UTF-32 BOMs before UTF-16LE (FF FE 00 00 shares FF FE prefix with UTF-16LE).
    if (bytes.size >= 4 &&
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() &&
        bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
    ) {
        val aligned = ((bytes.size - 4) and 3.inv()).coerceAtLeast(0)
        return String(bytes, 4, aligned, java.nio.charset.Charset.forName("UTF-32LE"))
    }
    if (bytes.size >= 4 &&
        bytes[0] == 0x00.toByte() && bytes[1] == 0x00.toByte() &&
        bytes[2] == 0xFE.toByte() && bytes[3] == 0xFF.toByte()
    ) {
        val aligned = ((bytes.size - 4) and 3.inv()).coerceAtLeast(0)
        return String(bytes, 4, aligned, java.nio.charset.Charset.forName("UTF-32BE"))
    }
    val hasUtf16Bom =
        (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
            (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
    if (hasUtf16Bom) {
        val safeBytes = if (bytes.size > 3 && (bytes.size - 2) % 2 != 0) {
            bytes.copyOf(bytes.size - 1)
        } else bytes
        return SrtSubtitleParser.decode(safeBytes)
    }
    val hasUtf8Bom =
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    if (hasUtf8Bom) {
        return SrtSubtitleParser.decode(bytes)
    }

    // BOM-less UTF-16 or strict UTF-8 first via SrtSubtitleParser.
    val strictUtf8OrUtf16 = runCatching {
        val evenZeros = bytes.indices.count { it % 2 == 0 && bytes[it] == 0.toByte() }
        val oddZeros = bytes.indices.count { it % 2 == 1 && bytes[it] == 0.toByte() }
        val evenCount = (bytes.size + 1) / 2
        val oddCount = bytes.size / 2
        val looksUtf16 = (oddCount > 0 && evenZeros >= 2 && evenZeros * 3 >= evenCount) ||
            (evenCount > 0 && oddZeros >= 2 && oddZeros * 3 >= oddCount)
        if (looksUtf16) {
            return@runCatching SrtSubtitleParser.decode(bytes)
        }
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    if (strictUtf8OrUtf16 != null) return strictUtf8OrUtf16.replace("\u0000", "")

    // Legacy 8-bit encodings: distinguish Windows-1256 (Arabic) vs Windows-1252 (Western/Latin).
    val win1256Strict = runCatching {
        java.nio.charset.Charset.forName("windows-1256").newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()
    val win1256Lenient = runCatching {
        String(bytes, java.nio.charset.Charset.forName("windows-1256"))
    }.getOrNull()
    val win1252 = runCatching {
        String(bytes, java.nio.charset.Charset.forName("windows-1252"))
    }.getOrNull()
    val arabicChars = (win1256Strict ?: win1256Lenient)?.count { it in '\u0600'..'\u06FF' } ?: 0
    if (preferArabicLegacy || arabicChars >= 2) {
        return (win1256Strict ?: win1256Lenient ?: win1252 ?: SrtSubtitleParser.decode(bytes))
            .replace("\u0000", "")
    }
    return (win1252 ?: win1256Lenient ?: SrtSubtitleParser.decode(bytes)).replace("\u0000", "")
}

/**
 * Normalizes decoded subtitle text into clean UTF-8 bytes (with UTF-8 BOM for SRT/ASS/SSA
 * so Media3 and external subtitle parsers handle Arabic/Unicode cues reliably).
 */
internal fun normalizeSubtitleForStorage(
    bytes: ByteArray,
    extension: String,
    preferArabicLegacy: Boolean = false
): ByteArray {
    val decoded = decodeSubtitleBytes(bytes, preferArabicLegacy)
        .trimStart('\uFEFF')
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    val converted = if (!extension.equals("vtt", ignoreCase = true) && !isAssDialogueText(decoded)) {
        convertLegacySubToSrtIfApplicable(decoded) ?: decoded
    } else decoded
    val normalized = converted.lines().joinToString("\n") { line ->
        if (line.contains("-->") && SUBTITLE_TIMESTAMP_REGEX.matches(line)) {
            cleanTimestampLine(line)
        } else {
            line
        }
    }
    val utf8Body = normalized.toByteArray(Charsets.UTF_8)
    return if (extension.equals("vtt", ignoreCase = true)) {
        utf8Body
    } else {
        UTF8_BOM + utf8Body
    }
}

internal fun detectSubtitleFormat(bytes: ByteArray, preferArabicLegacy: Boolean = false): String? {
    if (isHtmlOrCloudflarePayload(bytes)) return null
    val decodedText = runCatching { decodeSubtitleBytes(bytes, preferArabicLegacy) }.getOrNull() ?: return null
    val text = decodedText.trimStart('\uFEFF', ' ', '\t', '\n', '\r')
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    if (isAssDialogueText(text)) return "ass"
    val hasCue = SUBTITLE_TIMESTAMP_REGEX.containsMatchIn(text)
    if (hasCue) {
        return if (text.startsWith("WEBVTT", ignoreCase = true)) "vtt" else "srt"
    }
    if (convertLegacySubToSrtIfApplicable(text) != null) return "srt"
    return null
}

internal fun isHtmlOrCloudflarePayload(bytes: ByteArray, contentType: String? = null): Boolean {
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (mime == "text/html" || mime == "application/xhtml+xml") return true
    val head = runCatching {
        String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
    }.getOrDefault("").trimStart('\uFEFF', ' ', '\t', '\n', '\r').lowercase(Locale.ROOT)
    if (head.startsWith("<!doctype html") ||
        head.startsWith("<html") ||
        head.startsWith("<head") ||
        head.startsWith("<body")
    ) return true
    val looksLikeCloudflareChallenge = head.contains("just a moment...") ||
        head.contains("cf-browser-verification") ||
        head.contains("attention required! | cloudflare") ||
        head.contains("enable javascript and cookies to continue")
    if (!looksLikeCloudflareChallenge) return false
    // Real English subtitles can contain "Just a moment..." in early dialogue lines.
    // Only reject as Cloudflare when no subtitle timing/dialogue structure exists.
    return !SUBTITLE_TIMESTAMP_REGEX.containsMatchIn(head) && !isAssDialogueText(head)
}

internal fun validateSubtitleDownloadResponse(
    contentType: String?,
    bytes: ByteArray,
    providerName: String = "مزود الترجمة"
) {
    require(bytes.isNotEmpty()) { "ملف الترجمة فارغ" }
    unsupportedArchiveTypeMessage(bytes)?.let { error(it) }
    val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
    if (isHtmlOrCloudflarePayload(bytes, mime)) {
        error(
            "تم حجب تنزيل الترجمة من $providerName بواسطة حماية Cloudflare أو صفحة HTML غير صالحة. جرّب نتيجة أخرى أو تحقّق من مفتاح API."
        )
    }
    val trimmedHead = runCatching {
        String(bytes, 0, minOf(bytes.size, 2048), Charsets.UTF_8)
    }.getOrDefault("").trimStart('\uFEFF', ' ', '\t', '\n', '\r')
    if (mime.contains("json") || (trimmedHead.startsWith("{") && !trimmedHead.startsWith("{1}"))) {
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrDefault("")
        val apiObj = runCatching { JSONObject(text) }.getOrNull()
        if (apiObj != null && (mime.contains("json") || apiObj.has("error") || apiObj.has("message") || apiObj.has("status"))) {
            val apiMsg = apiObj.optString("error").ifBlank { apiObj.optString("message") }
                .takeIf { it.isNotBlank() }
            error(apiMsg ?: "أعاد $providerName استجابة JSON بدلاً من ملف الترجمة.")
        }
    }
    val looksZip = bytes.size > 3 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
    val looksGzip = bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()
    if (mime.isNotBlank()) {
        val allowedMime = mime == "application/zip" ||
            mime == "application/x-zip-compressed" ||
            mime == "application/x-zip" ||
            mime == "application/x-gzip" ||
            mime == "application/gzip" ||
            mime == "application/x-subrip" ||
            mime == "application/srt" ||
            mime == "application/x-subtitles" ||
            mime == "application/octet-stream" ||
            mime.startsWith("text/") ||
            mime.contains("subrip") ||
            mime.contains("subtitle")
        if (!allowedMime && !looksZip && !looksGzip) {
            error("نوع استجابة التنزيل من $providerName غير صالح ($mime).")
        }
        if ((mime == "application/zip" || mime == "application/x-zip-compressed" || mime == "application/x-zip") && !looksZip) {
            error("أعاد $providerName نوع application/zip لكن محتوى الأرشيف غير صالح.")
        }
    }
}

/** Strict content/extension check shared by the cache writer and unit tests. */
internal fun isValidSubtitlePayload(
    bytes: ByteArray,
    extension: String,
    preferArabicLegacy: Boolean = false
): Boolean {
    if (extension !in setOf("srt", "ass", "ssa", "vtt") ||
        bytes.isEmpty() || bytes.size > 8 * 1024 * 1024
    ) return false
    if (isHtmlOrCloudflarePayload(bytes)) return false

    val detected = detectSubtitleFormat(bytes, preferArabicLegacy) ?: return false
    return if (extension == "ssa") detected == "ass" else detected == extension
}

class OnlineSubtitleRepository(context: Context, private val vault: NetworkCredentialVault) {
    private val app = context.applicationContext
    private val client = OkHttpClient.Builder()
        // Provider API calls carry API keys/tokens; never follow redirects that
        // could forward custom credential headers to another origin.
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    // Download redirects are followed manually so every hop is checked before
    // connecting and credentials are never forwarded to a different host.
    private val subf2mProvider = Subf2mSubtitleProvider()
    private val tvMazeEpisodeGuideProvider = TvMazeEpisodeGuideProvider()
    private val downloadClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val publicSearchCache = SubtitleSearchResultCache(12)
    private val automaticSearchCache = SubtitleSearchResultCache(12)
    // Coalesce simultaneous identical searches so guest-provider quotas are not wasted.
    private val automaticSearchInFlight = ConcurrentHashMap<String, FutureTask<List<OnlineSubtitle>>>()
    private data class CachedOpenSubtitlesToken(val value: String, val expiresAtMs: Long)
    private val tokenLock = Any()
    @Volatile private var cachedOpenSubtitlesToken: CachedOpenSubtitlesToken? = null

    /** Fetches a separately sourced, exact-title TV episode guide; does not affect subtitle providers. */
    fun searchTvEpisodeGuide(title: String, forceRefresh: Boolean = false): TvEpisodeGuide? {
        val cleaned = SubtitleQueryCleaner.clean(title)
        require(cleaned.title.isNotBlank()) { "أدخل اسم المسلسل أولاً" }
        return tvMazeEpisodeGuideProvider.search(cleaned.title, cleaned.year, forceRefresh)
    }

    fun saveCredentials(apiKey: String, username: String, password: String) {
        require(apiKey.isNotBlank() && username.isNotBlank() && password.isNotBlank())
        vault.save(KEY_SLOT, apiKey.trim(), "")
        vault.save(USER_SLOT, username.trim(), password)
        cachedOpenSubtitlesToken = null
        automaticSearchCache.clear()
        publicSearchCache.clear()
    }

    fun hasCredentials(): Boolean = runCatching {
        val key = vault.load(KEY_SLOT)?.first
        val user = vault.load(USER_SLOT)
        !key.isNullOrBlank() && !user?.first.isNullOrBlank() && !user?.second.isNullOrBlank()
    }.getOrDefault(false)

    fun hasSubdlKey(): Boolean = runCatching {
        !vault.load(SUBDL_KEY_SLOT)?.first.isNullOrBlank()
    }.getOrDefault(false)

    fun hasWebsiteKey(): Boolean = runCatching {
        !vault.load(WEBSITE_KEY_SLOT)?.first.isNullOrBlank()
    }.getOrDefault(false)

    fun saveProviderApiKeys(subdlKey: String, websiteKey: String) {
        if (subdlKey.isNotBlank()) vault.save(SUBDL_KEY_SLOT, subdlKey.trim(), "api-key")
        if (websiteKey.isNotBlank()) vault.save(WEBSITE_KEY_SLOT, websiteKey.trim(), "api-key")
        automaticSearchCache.clear()
        publicSearchCache.clear()
    }

    /** Downloads (or reuses cached) subtitle and extracts clean cue lines for quick preview. */
    suspend fun previewSubtitleLines(subtitle: OnlineSubtitle, maxCues: Int = 5): List<String> =
        withContext(Dispatchers.IO) {
            val uri = download(subtitle)
            val path = uri.path ?: error("تعذر الوصول إلى ملف المعاينة")
            val file = File(path)
            val decoded = SrtSubtitleParser.decode(file.readBytes())
            val blocks = SrtSubtitleParser.splitBlocks(decoded, file.extension)
            val markup = Regex("""<[^>]+>|\{\\[^}]*}""")
            blocks.asSequence()
                .mapNotNull { block ->
                    val lines = block.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    val timingIdx = SrtSubtitleParser.timingLineIndex(lines)
                    val timing = if (timingIdx >= 0) lines[timingIdx].substringBefore("-->").trim() else null
                    val bodyStart = if (timingIdx >= 0) timingIdx + 1 else 0
                    val text = lines.drop(bodyStart)
                        .joinToString(" ") { markup.replace(it, "").trim() }
                        .trim()
                    if (text.isBlank()) null
                    else if (timing != null) "[$timing] $text"
                    else text
                }
                .take(maxCues.coerceIn(1, 12))
                .toList()
        }

    /**
     * Searches a public guest endpoint first. No account, API key, or setup is required
     * for the user. The provider documents a small daily guest quota, so failures are
     * surfaced cleanly and can be retried later.
     */
    fun searchAutomatic(title: String, language: String, season: Int?, episode: Int?, mediaUri: Uri? = null): List<OnlineSubtitle> {
        val cleaned = SubtitleQueryCleaner.clean(title)
        require(cleaned.title.isNotBlank()) { "أدخل اسم الفيلم أو المسلسل" }
        val resolvedSeason = season ?: cleaned.season
        val resolvedEpisode = episode ?: cleaned.episode
        require((resolvedSeason == null) == (resolvedEpisode == null)) {
            "أدخل الموسم والحلقة معاً للبحث عن ترجمة المسلسلات"
        }
        val providerTitle = cleaned.title + (cleaned.year?.let { " $it" } ?: "")
        val queryKey = buildString {
            append(providerTitle.trim().lowercase(Locale.ROOT))
            append("|s").append(resolvedSeason ?: 0).append("e").append(resolvedEpisode ?: 0)
            append("|").append(mediaUri?.toString().orEmpty())
        }

        // Share one network fan-out for simultaneous identical searches.
        // Each caller applies its own language filter to the combined results.
        val candidate = FutureTask {
            searchAutomaticUncached(queryKey, cleaned, resolvedSeason, resolvedEpisode, mediaUri)
        }
        // putIfAbsent is available on the minimum supported Android API (23), unlike
        // ConcurrentHashMap.computeIfAbsent, which was added to Android in API 24.
        val task = automaticSearchInFlight.putIfAbsent(queryKey, candidate) ?: candidate
        if (task === candidate) candidate.run()
        try {
            return SubtitleResultPolicy.rankAndFilter(task.get(), language, resolvedSeason, resolvedEpisode)
                .take(40)
                .map { it.copy(seasonNumber = resolvedSeason, episodeNumber = resolvedEpisode) }
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw java.io.IOException("تم إيقاف البحث عن الترجمة", interrupted)
        } catch (failed: ExecutionException) {
            val cause = failed.cause
            if (cause is Exception) throw cause
            throw IllegalStateException("تعذر البحث عن الترجمة", cause)
        } finally {
            automaticSearchInFlight.remove(queryKey, task)
        }
    }

    private fun searchAutomaticUncached(
        queryKey: String,
        cleaned: CleanSubtitleQuery,
        season: Int?,
        episode: Int?,
        mediaUri: Uri?
    ): List<OnlineSubtitle> {
        automaticSearchCache.get(queryKey)?.let { return it }

        // SubDL is primary. SubF2M is queried only when SubDL fails or lacks
        // either Arabic or English candidates; the public index remains tertiary.
        val subdlKey = runCatching { vault.load(SUBDL_KEY_SLOT)?.first }
            .getOrNull()?.takeIf { it.isNotBlank() }
        val subdlAttempt = if (subdlKey != null) runCatching {
            val exactFileResults = runCatching {
                searchSubDlFilename(mediaUri, season, episode, subdlKey)
            }.getOrDefault(emptyList())
            val titleResults = SubtitleResultPolicy.withEpisodeFallback(
                season,
                episode,
                episodeSearch = {
                    searchSubDl(cleaned.title, "ar,en", season, episode, cleaned.year, subdlKey)
                },
                titleSearch = {
                    searchSubDl(
                        cleaned.title, "ar,en", null, null, cleaned.year,
                        subdlKey, tvSearch = season != null || episode != null
                    )
                },
                shouldFallback = { results ->
                    results.map { SubtitleQueryCleaner.normalizeLanguage(it.language) }
                        .toSet().containsAll(setOf("ar", "en")).not()
                }
            )
            (exactFileResults + titleResults).distinctBy { it.provider + ":" + it.sourceId }
        } else null

        val primaryResults = subdlAttempt?.getOrDefault(emptyList()).orEmpty()
        val primaryLanguages = primaryResults.map {
            SubtitleQueryCleaner.normalizeLanguage(it.language)
        }.toSet()
        val needsBackup = primaryResults.isEmpty() ||
            !primaryLanguages.containsAll(setOf("ar", "en"))
        // An IMDb ID is only safe for episode-scoped TV searches. For title-only
        // searches, movie and series identities can share the same display title.
        val imdbId = if (needsBackup && subdlKey != null && season != null && episode != null) runCatching {
            searchSubDlTvIdentity(cleaned.title, cleaned.year, subdlKey, "tv")
        }.getOrNull() else null
        val subf2mAttempt = if (needsBackup) runCatching {
            subf2mProvider.search(cleaned.title, cleaned.year, season, episode, imdbId)
        } else null
        val backupResults = subf2mAttempt?.getOrDefault(emptyList()).orEmpty()
        val foundLanguages = (primaryResults + backupResults).map {
            SubtitleQueryCleaner.normalizeLanguage(it.language)
        }.toSet()
        val publicAttempt = if (!foundLanguages.containsAll(setOf("ar", "en"))) {
            runCatching { searchPublic(cleaned.title, cleaned.year, season, episode) }
        } else null

        val combined = (primaryResults + backupResults +
            publicAttempt?.getOrDefault(emptyList()).orEmpty())
            .distinctBy { it.provider + ":" + it.sourceId }
        val failures = listOfNotNull(
            subdlAttempt?.exceptionOrNull(),
            subf2mAttempt?.exceptionOrNull(),
            publicAttempt?.exceptionOrNull()
        )
        if (combined.isEmpty() && failures.isNotEmpty()) {
            val actionableFailure = failures.firstOrNull {
                it is SubtitleHttpException && it.statusCode in setOf(401, 403, 429)
            } ?: failures.last()
            throw actionableFailure
        }
        automaticSearchCache.put(queryKey, combined)
        return combined
    }

    private fun searchPublic(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?
    ): List<OnlineSubtitle> {
        require(title.isNotBlank()) { "أدخل اسم الفيلم أو المسلسل" }
        val cleanTitle = title.trim().take(150)
        val titleWithYear = cleanTitle + (year?.let { " $it" } ?: "")
        val candidates = buildList {
            add(SubtitleQueryCleaner.appendEpisodeIfMissing(titleWithYear, season, episode))
            if (year != null) {
                add(SubtitleQueryCleaner.appendEpisodeIfMissing(cleanTitle, season, episode))
            }
            if (season != null || episode != null) add(cleanTitle)
        }.map { it.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase(Locale.ROOT) }

        val failures = mutableListOf<Exception>()
        val candidateResults = mutableListOf<List<OnlineSubtitle>>()
        candidates.forEach { query ->
            val result = runCatching { searchPublicQuery(query) }
            if (result.isSuccess) {
                // Do not stop at an English-only hit: the selected Arabic filter is
                // applied after provider fan-out, and a weaker title may find Arabic.
                candidateResults += result.getOrDefault(emptyList())
            } else {
                val failure = result.exceptionOrNull() ?: return@forEach
                if (failure !is Exception) throw failure
                failures += failure
                // Authentication and quota failures are query-independent. Retrying
                // the same provider with weaker titles only burns the guest/API quota.
                if (failure is SubtitleHttpException &&
                    failure.statusCode in setOf(401, 403, 429)
                ) throw failure
            }
        }
        if (candidateResults.isEmpty() && failures.size == candidates.size && failures.isNotEmpty()) {
            throw failures.last()
        }
        return SubtitleResultPolicy.combineCandidates(candidateResults)
    }

    private fun searchPublicQuery(query: String): List<OnlineSubtitle> {
        val cacheKey = query.lowercase(Locale.ROOT)
        publicSearchCache.get(cacheKey)?.let { return it }
        val url = Uri.parse("$PUBLIC_API/search/subtitles").buildUpon()
            .appendQueryParameter("title", query)
            .build().toString()
        val request = Request.Builder().url(url)
            .header("User-Agent", AGENT).header("Accept", "application/json")
            .apply {
                val bearer = vault.load(WEBSITE_KEY_SLOT)?.first
                if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
            }.get().build()
        val data = execute(request).optJSONArray("data") ?: return emptyList()
        val parsed = buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                val format = SubtitleProviderResultParser.format(item.optString("format")) ?: continue
                val languageValue = item.opt("language")
                val languageCode = when (languageValue) {
                    is JSONObject -> languageValue.optString("code", "en")
                    null -> "en"
                    else -> languageValue.toString()
                }
                add(OnlineSubtitle(
                    fileId = 0L,
                    language = SubtitleProviderResultParser.language(languageCode),
                    release = SubtitleProviderResultParser.release(
                        item.optString("release"), item.optString("film_title", "Subtitle")
                    ),
                    downloads = SubtitleProviderResultParser.downloadCount(item.optInt("downloads")),
                    format = format,
                    sourceId = id,
                    provider = PUBLIC_PROVIDER
                ))
            }
        }.distinctBy { it.sourceId }.take(40)
        publicSearchCache.put(cacheKey, parsed)
        return parsed
    }

    /**
     * Downloads on IO and keeps the active OkHttp call cancellable until its body
     * has been fully consumed, so the player's Cancel action closes a blocked read.
     */
    suspend fun download(subtitle: OnlineSubtitle): Uri = withContext(Dispatchers.IO) {
        when (subtitle.provider) {
            PUBLIC_PROVIDER -> downloadPublic(subtitle)
            SUBDL_PROVIDER -> downloadSubDl(subtitle)
            SUBF2M_PROVIDER -> downloadSubF2m(subtitle)
            else -> error("مزود الترجمة لهذا الملف غير مدعوم")
        }
    }

    suspend fun download(fileId: Long): Uri = withContext(Dispatchers.IO) {
        error("لم يعد تنزيل OpenSubtitles مدعوماً؛ أعد البحث عبر المزودين الحاليين")
    }

    private suspend fun downloadPublic(subtitle: OnlineSubtitle): Uri {
        require(subtitle.sourceId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            "معرّف الترجمة غير صالح"
        }
        val dir = File(app.cacheDir, "online-subtitles").apply { mkdirs() }
        val cachePrefix = "subtitle-public-${subtitleCacheToken(subtitle.sourceId)}"
        findReusableCachedSubtitle(dir, cachePrefix)?.let { return Uri.fromFile(it) }

        val endpoint = "$PUBLIC_API/search/download/${subtitle.sourceId}"
        val request = Request.Builder().url(endpoint)
            .header("User-Agent", AGENT)
            .header("Accept", "application/json")
            .apply {
                val bearer = vault.load(WEBSITE_KEY_SLOT)?.first
                if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
            }.get().build()
        val downloadPath = executeDownloadApi(request).optString("download_url")
            .takeIf { it.isNotBlank() } ?: error("لم توفر الخدمة رابط تنزيل للترجمة")
        val downloadUri = when {
            downloadPath.startsWith("https://") -> Uri.parse(downloadPath)
            downloadPath.startsWith("/strapi/") -> Uri.parse("https://$PUBLIC_HOST$downloadPath")
            else -> Uri.parse("$PUBLIC_BASE/${downloadPath.trimStart('/')}")
        }
        require(isTrustedHttpsHost(downloadUri, PUBLIC_HOST)) {
            "رابط تنزيل الترجمة غير آمن"
        }
        val downloadRequest = Request.Builder().url(downloadUri.toString())
            .header("User-Agent", AGENT)
            .header("Accept", "*/*")
            .apply {
                val bearer = vault.load(WEBSITE_KEY_SLOT)?.first
                if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
            }.get().build()
        return withDownloadResponse(downloadRequest, PUBLIC_HOST) { response ->
            require(isTrustedHttpsUrl(response.request.url.toString(), PUBLIC_HOST)) {
                "حوّل مزود الترجمة التنزيل إلى نطاق غير موثوق"
            }
            val contentType = response.header("Content-Type")
            val rawBytes = response.body?.byteStream()?.use(::readBounded) ?: byteArrayOf()
            if (!response.isSuccessful) {
                if (isHtmlOrCloudflarePayload(rawBytes, contentType)) {
                    error("تم حجب تنزيل الترجمة بواسطة حماية Cloudflare (HTTP ${response.code}).")
                }
                error("تعذر تنزيل الترجمة (HTTP ${response.code})")
            }
            val bytes = unwrapGzipIfNeeded(rawBytes)
            validateSubtitleDownloadResponse(contentType, bytes, "subtitles.website")
            val preferArabic = subtitle.language.equals("ar", ignoreCase = true)
            val target = if (bytes.isZip()) {
                extract(bytes, dir, 0L, cachePrefix, subtitle.format, preferArabic)
            } else {
                val ext = detect(bytes, preferArabic)
                    ?: error("محتوى ملف الترجمة غير صالح: لا يحتوي على أسطر توقيت صالحة")
                val normalized = normalizeSubtitleForStorage(bytes, ext, preferArabic)
                writeSubtitleAtomically(dir, "$cachePrefix.$ext", normalized)
            }
            require(target.length() in 1..MAX_BYTES.toLong()) { "حجم ملف الترجمة غير صالح" }
            Uri.fromFile(target)
        }
    }

    /**
     * Uses SubDL v2 filename matching only when the actual video filename itself
     * contains the requested season/episode. The endpoint's exact scope is designed
     * to return subtitles for that release, not a neighbouring episode.
     */
    /**
     * Resolves the movie or TV show's IMDb id through SubDL's documented v2 title search.
     */
    private fun searchSubDlTvIdentity(
        title: String, year: Int?, apiKey: String, mediaType: String = "tv"
    ): String? {
        val url = Uri.parse(SUBDL_V2_MOVIES_API).buildUpon()
            .appendQueryParameter("q", (title + (year?.let { " $it" } ?: "")).take(180))
            .appendQueryParameter("type", mediaType)
            .build().toString()
        val payload = execute(
            Request.Builder().url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", AGENT)
                .header("Accept", "application/json")
                .get().build()
        )
        val results = payload.optJSONArray("results") ?: return null
        return SubtitleProviderResultParser.subDlImdbId(results, title, year, mediaType)
    }

    private suspend fun downloadSubF2m(subtitle: OnlineSubtitle): Uri {
        val detailUrl = subtitle.downloadUrl ?: error("رابط صفحة SubF2M غير متوفر؛ أعد البحث")
        val directory = File(app.cacheDir, "online-subtitles").apply { mkdirs() }
        val prefix = "subtitle-subf2m-${subtitleCacheToken(subtitle.sourceId)}"
        findReusableCachedSubtitle(directory, prefix)?.let { return Uri.fromFile(it) }
        val rawBytes = subf2mProvider.download(detailUrl)
        val bytes = unwrapGzipIfNeeded(rawBytes)
        unsupportedArchiveTypeMessage(bytes)?.let { error(it) }
        val preferArabic = subtitle.language.equals("ar", ignoreCase = true)
        val target = if (bytes.isZip()) {
            extract(
                bytes, directory, 0L, prefix, subtitle.format, preferArabic,
                subtitle.seasonNumber, subtitle.episodeNumber
            )
        } else {
            val extension = detect(bytes, preferArabic)
                ?: error("محتوى ملف الترجمة غير صالح: لا يحتوي على أسطر توقيت صالحة")
            val normalized = normalizeSubtitleForStorage(bytes, extension, preferArabic)
            writeSubtitleAtomically(directory, "$prefix.$extension", normalized)
        }
        require(target.length() in 1..MAX_BYTES.toLong()) { "حجم ملف الترجمة غير صالح" }
        return Uri.fromFile(target)
    }

    private fun searchSubDlFilename(
        mediaUri: Uri?,
        season: Int?,
        episode: Int?,
        apiKey: String
    ): List<OnlineSubtitle> {
        if (mediaUri == null || season == null || episode == null) return emptyList()
        val displayName = runCatching {
            app.contentResolver.query(
                mediaUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val displayNameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (displayNameColumn >= 0) cursor.getString(displayNameColumn) else null
                } else null
            }
        }.getOrNull().orEmpty().ifBlank { mediaUri.lastPathSegment.orEmpty() }
        if (displayName.isBlank()) return emptyList()
        val parsed = SubtitleQueryCleaner.clean(displayName)
        if (parsed.season != season || parsed.episode != episode) return emptyList()

        val url = Uri.parse(SUBDL_V2_FILES_API).buildUpon()
            .appendQueryParameter("filename", displayName.take(240))
            .appendQueryParameter("languages", "AR,EN")
            .appendQueryParameter("type", "tv")
            .appendQueryParameter("subs_per_page", "30")
            .build().toString()
        val payload = execute(
            Request.Builder().url(url)
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", AGENT)
                .header("Accept", "application/json")
                .get().build()
        )
        if (payload.has("status") && !payload.optBoolean("status")) {
            error(payload.optString("error").takeIf { it.isNotBlank() }
                ?: "رفضت خدمة SubDL v2 طلب مطابقة اسم الملف")
        }
        val identityResults = payload.optJSONArray("results") ?: return emptyList()
        if (!SubtitleProviderResultParser.subDlHasExactTitleMatch(identityResults, parsed.title, parsed.year, "tv")) {
            return emptyList()
        }
        val match = payload.optJSONObject("match")
        if (match != null) {
            val matchedSeason = match.optInt("season", -1)
            val matchedEpisode = match.optInt("episode", -1)
            if (match.optBoolean("full_season") ||
                (matchedSeason > 0 && matchedSeason != season) ||
                (matchedEpisode > 0 && matchedEpisode != episode)
            ) return emptyList()
        }
        val matchedSeason = match?.optInt("season")?.takeIf { it > 0 }
        val matchedEpisode = match?.optInt("episode")?.takeIf { it > 0 }
        val subtitles = payload.optJSONArray("subtitles") ?: return emptyList()
        return buildList {
            for (index in 0 until subtitles.length()) {
                val item = subtitles.optJSONObject(index) ?: continue
                val parsedSubtitle = SubtitleProviderResultParser.subDlSubtitle(
                    url = item.optString("url"),
                    releaseName = item.optString("release_name").ifBlank { item.optString("name") },
                    languageCode = item.optString("lang").ifBlank { item.optString("language") },
                    formatName = item.optString("format"),
                    downloads = item.optInt("downloads"),
                    fallbackTitle = displayName,
                    providerSeason = item.optInt("season").takeIf { it > 0 } ?: matchedSeason,
                    providerEpisode = item.optInt("episode").takeIf { it > 0 } ?: item.optInt("episode_from").takeIf { it > 0 } ?: matchedEpisode,
                    providerEpisodeEnd = item.optInt("episode_end").takeIf { it > 0 },
                    fullSeasonPack = item.optBoolean("full_season") || match?.optBoolean("full_season") == true
                ) ?: continue
                add(parsedSubtitle)
            }
        }.distinctBy { it.sourceId }.take(40)
    }

    private fun searchSubDl(
        title: String,
        language: String,
        season: Int?,
        episode: Int?,
        year: Int?,
        apiKey: String,
        tvSearch: Boolean = season != null || episode != null
    ): List<OnlineSubtitle> {
        val languages = language.split(',').map(SubtitleQueryCleaner::normalizeLanguage)
            .filter { it in setOf("ar", "en") }.joinToString(",") { it.uppercase(Locale.ROOT) }
        val url = Uri.parse(SUBDL_API).buildUpon()
            .appendQueryParameter("api_key", apiKey)
            .appendQueryParameter("film_name", title.take(150))
            .apply {
                if (tvSearch || season != null || episode != null) appendQueryParameter("type", "tv")
                if (year != null) appendQueryParameter("year", year.toString())
            }
            .appendQueryParameter("languages", languages.ifBlank { "AR,EN" })
            .appendQueryParameter("subs_per_page", "30")
            .appendQueryParameter("unpack", "1")
            .apply {
                season?.let { appendQueryParameter("season_number", it.toString()) }
                episode?.let { appendQueryParameter("episode_number", it.toString()) }
            }.build().toString()
        val payload = execute(Request.Builder().url(url).header("User-Agent", AGENT)
            .header("Accept", "application/json").get().build())
        if (payload.has("status") && !payload.optBoolean("status")) {
            error(payload.optString("error").takeIf { it.isNotBlank() }
                ?: "رفضت خدمة SubDL طلب البحث")
        }
        val identityResults = payload.optJSONArray("results") ?: return emptyList()
        val requestedType = if (tvSearch || season != null || episode != null) "tv" else "any"
        if (!SubtitleProviderResultParser.subDlHasExactTitleMatch(identityResults, title, year, requestedType)) {
            return emptyList()
        }
        val data = payload.optJSONArray("subtitles") ?: return emptyList()
        return buildList {
            for (index in 0 until data.length()) {
                val parent = data.optJSONObject(index) ?: continue
                val unpacked = parent.optJSONArray("unpack_files")
                if (unpacked != null && unpacked.length() > 0) {
                    for (fileIndex in 0 until unpacked.length()) {
                        val file = unpacked.optJSONObject(fileIndex) ?: continue
                        val parsed = SubtitleProviderResultParser.subDlSubtitle(
                            url = file.optString("url"),
                            releaseName = file.optString("release_name").ifBlank { file.optString("name") },
                            languageCode = file.optString("language").ifBlank { file.optString("lang") },
                            formatName = file.optString("format"),
                            downloads = file.optInt("downloads"),
                            fallbackTitle = title,
                            providerSeason = file.optInt("season").takeIf { it > 0 } ?: parent.optInt("season").takeIf { it > 0 },
                            providerEpisode = file.optInt("episode").takeIf { it > 0 } ?: file.optInt("episode_from").takeIf { it > 0 } ?: parent.optInt("episode").takeIf { it > 0 } ?: parent.optInt("episode_from").takeIf { it > 0 },
                            providerEpisodeEnd = file.optInt("episode").takeIf { it > 0 }?.let {
                                file.optInt("episode_end").takeIf { it > 0 } ?: it
                            } ?: parent.optInt("episode_end").takeIf { it > 0 },
                            fullSeasonPack = if (file.has("full_season")) file.optBoolean("full_season")
                                else parent.optBoolean("full_season")
                        ) ?: continue
                        add(parsed)
                    }
                } else {
                    val parsed = SubtitleProviderResultParser.subDlSubtitle(
                        url = parent.optString("url"),
                        releaseName = parent.optString("release_name").ifBlank { parent.optString("name") },
                        languageCode = parent.optString("language").ifBlank { parent.optString("lang") },
                        formatName = parent.optString("format"),
                        downloads = parent.optInt("downloads"),
                        fallbackTitle = title,
                        providerSeason = parent.optInt("season").takeIf { it > 0 },
                        providerEpisode = parent.optInt("episode").takeIf { it > 0 } ?: parent.optInt("episode_from").takeIf { it > 0 },
                        providerEpisodeEnd = parent.optInt("episode_end").takeIf { it > 0 },
                        fullSeasonPack = parent.optBoolean("full_season")
                    ) ?: continue
                    add(parsed)
                }
            }
        }.distinctBy { it.sourceId }.take(40)
    }

    private suspend fun downloadSubDl(subtitle: OnlineSubtitle): Uri {
        val path = subtitle.sourceId
        val downloadUri = when {
            path.startsWith("https://") -> Uri.parse(path)
            path.startsWith("//") -> Uri.parse("https:$path")
            path.startsWith("/") -> Uri.parse("https://dl.subdl.com$path")
            else -> Uri.parse("https://dl.subdl.com/$path")
        }
        require(isTrustedHttpsHost(downloadUri, "subdl.com")) {
            "رابط تنزيل ترجمة SubDL غير آمن"
        }
        val directory = File(app.cacheDir, "online-subtitles").apply { mkdirs() }
        val prefix = "subtitle-subdl-${subtitleCacheToken(subtitle.sourceId)}"
        findReusableCachedSubtitle(directory, prefix)?.let { return Uri.fromFile(it) }

        val downloadRequest = Request.Builder().url(downloadUri.toString())
            .header("User-Agent", AGENT)
            .header("Accept", "*/*")
            .apply {
                val key = vault.load(SUBDL_KEY_SLOT)?.first
                if (!key.isNullOrBlank()) header("x-api-key", key)
            }.get().build()
        return withDownloadResponse(downloadRequest, "subdl.com") { response ->
            require(isTrustedHttpsUrl(response.request.url.toString(), "subdl.com")) {
                "حوّل SubDL التنزيل إلى نطاق غير موثوق"
            }
            val contentType = response.header("Content-Type")
            val rawBytes = response.body?.byteStream()?.use(::readBounded) ?: byteArrayOf()
            if (!response.isSuccessful) {
                if (isHtmlOrCloudflarePayload(rawBytes, contentType)) {
                    error("تم حجب تنزيل الترجمة من SubDL بواسطة حماية Cloudflare (HTTP ${response.code}). جرّب نتيجة أخرى أو أضف مفتاح SubDL API.")
                }
                error("تعذر تنزيل ترجمة SubDL (HTTP ${response.code})")
            }
            val bytes = unwrapGzipIfNeeded(rawBytes)
            validateSubtitleDownloadResponse(contentType, bytes, "SubDL")
            val preferArabic = subtitle.language.equals("ar", ignoreCase = true)
            val target = if (bytes.isZip()) {
                extract(
                    bytes, directory, 0L, prefix, subtitle.format, preferArabic,
                    subtitle.seasonNumber, subtitle.episodeNumber
                )
            } else {
                val ext = detect(bytes, preferArabic)
                    ?: error("محتوى ملف الترجمة غير صالح: لا يحتوي على أسطر توقيت صالحة")
                val normalized = normalizeSubtitleForStorage(bytes, ext, preferArabic)
                writeSubtitleAtomically(directory, "$prefix.$ext", normalized)
            }
            require(target.length() in 1..MAX_BYTES.toLong()) { "حجم ملف الترجمة غير صالح" }
            Uri.fromFile(target)
        }
    }

    fun search(
        title: String, language: String, season: Int?, episode: Int?,
        mediaUri: Uri? = null, parentImdbId: String? = null
    ): List<OnlineSubtitle> {
        val key = credentials().first
        require(title.isNotBlank()) { "أدخل اسم الفيلم أو المسلسل" }
        val url = Uri.parse("$BASE/subtitles").buildUpon()
            .appendQueryParameter("query", title.trim().take(180).lowercase(Locale.ROOT))
            .appendQueryParameter("languages", language)
            .apply {
                if (mediaUri != null) {
                    runCatching {
                        val hash = OpenSubtitlesMovieHash.calculate(app, mediaUri)
                        val size = if (mediaUri.scheme == "file") File(requireNotNull(mediaUri.path)).length()
                            else app.contentResolver.openFileDescriptor(mediaUri, "r")?.use { it.statSize } ?: -1L
                        if (size > 0L) {
                            appendQueryParameter("moviehash", hash)
                            appendQueryParameter("moviebytesize", size.toString())
                        }
                    }
                }
                if (season != null && episode != null) {
                    appendQueryParameter("type", "episode")
                    appendQueryParameter("season_number", season.toString())
                    appendQueryParameter("episode_number", episode.toString())
                    parentImdbId?.removePrefix("tt")?.toLongOrNull()?.let {
                        appendQueryParameter("parent_imdb_id", it.toString())
                    }
                }
            }.build().toString()
        val req = Request.Builder().url(url).header("Api-Key", key).header("User-Agent", AGENT)
            .header("Accept", "application/json").get().build()
        val data = execute(req).optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val feature = a.optJSONObject("feature_details")
                val providerSeason = feature?.optInt("season_number", -1) ?: -1
                val providerEpisode = feature?.optInt("episode_number", -1) ?: -1
                val providerRelease = a.optString("release", "Subtitle")
                val release = if (providerSeason > 0 && providerEpisode > 0 &&
                    !Regex("""(?i)\bS\d{1,2}E\d{1,3}\b|\b\d{1,2}x\d{1,3}\b""")
                        .containsMatchIn(providerRelease)
                ) {
                    providerRelease + " S%02dE%02d".format(Locale.ROOT, providerSeason, providerEpisode)
                } else providerRelease
                val files = a.optJSONArray("files") ?: continue
                for (j in 0 until files.length()) {
                    val f = files.optJSONObject(j) ?: continue
                    val id = f.optLong("file_id")
                    val ext = f.optString("file_name").substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (id > 0 && ext in FORMATS) add(
                        OnlineSubtitle(id, a.optString("language"), release,
                            a.optInt("download_count"), ext)
                    )
                }
            }
        }.distinctBy { it.fileId }.take(40)
    }

    private suspend fun downloadOpenSubtitles(fileId: Long): Uri {
        require(fileId > 0)
        val dir = File(app.cacheDir, "online-subtitles").apply { mkdirs() }
        findReusableCachedSubtitle(dir, "subtitle-$fileId")?.let { return Uri.fromFile(it) }
        val (key, account) = credentials()
        suspend fun downloadLink(token: String): String {
            val body = JSONObject().put("file_id", fileId).toString().toRequestBody(JSON)
            val request = Request.Builder().url("$BASE/download").header("Api-Key", key)
                .header("Authorization", "Bearer $token").header("User-Agent", AGENT)
                .post(body).build()
            return executeDownloadApi(request).optString("link")
                .takeIf { it.isNotBlank() } ?: error("لم توفر OpenSubtitles رابط تنزيل للترجمة")
        }
        val link = try {
            downloadLink(openSubtitlesToken(key, account))
        } catch (expired: SubtitleHttpException) {
            if (expired.statusCode != 401 && expired.statusCode != 403) throw expired
            cachedOpenSubtitlesToken = null
            downloadLink(openSubtitlesToken(key, account, forceRefresh = true))
        }
        val linkUri = Uri.parse(link)
        require(linkUri.scheme == "https" && !linkUri.host.isNullOrBlank() &&
            linkUri.userInfo == null && linkUri.port in listOf(-1, 443)
        ) { "رابط الترجمة غير آمن" }
        return withDownloadResponse(Request.Builder().url(linkUri.toString()).get().build()) { response ->
            if (response.request.url.isHttps.not() || response.request.url.username.isNotEmpty() ||
                response.request.url.password.isNotEmpty()
            ) error("حوّل OpenSubtitles التنزيل إلى رابط غير آمن")
            if (!response.isSuccessful) error("تعذر تنزيل الترجمة (HTTP ${response.code})")
            val rawBytes = response.body?.byteStream()?.use(::readBounded) ?: error("ملف الترجمة فارغ")
            val bytes = unwrapGzipIfNeeded(rawBytes)
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "حجم ملف الترجمة غير صالح" }
            val target = if (bytes.isZip()) extract(bytes, dir, fileId) else {
                val ext = detect(bytes) ?: error("محتوى ملف الترجمة غير صالح: لا يحتوي على أسطر توقيت صالحة")
                val normalized = normalizeSubtitleForStorage(bytes, ext)
                writeSubtitleAtomically(dir, "subtitle-$fileId.$ext", normalized)
            }
            require(target.length() in 1..MAX_BYTES.toLong())
            Uri.fromFile(target)
        }
    }

    private fun extract(
        bytes: ByteArray,
        dir: File,
        id: Long,
        outputPrefix: String = "subtitle-$id",
        preferredFormat: String? = null,
        preferArabicLegacy: Boolean = false,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null
    ): File {
        var bestCandidate: Triple<String, ByteArray, Int>? = null
        var sawSupportedExtensionEntry = false
        val episodeMatchRegex = if (seasonNumber != null && episodeNumber != null) {
            Regex("(?i)(?:\\bs0*${seasonNumber}\\s*e0*${episodeNumber}\\b|\\b0*${seasonNumber}x0*${episodeNumber}\\b)")
        } else null
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val entryName = entry.name
                val baseName = entryName.substringAfterLast('/')
                val isSystemNoise = entry.isDirectory ||
                    entryName.startsWith("__MACOSX/", ignoreCase = true) ||
                    baseName.startsWith("._")
                if (!isSystemNoise) {
                    val entryExt = baseName.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (entryExt in FORMATS || entryExt == "txt" || entryExt == "sub" || baseName == entryExt) {
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val n = readStreamWithProgress(zip, buffer)
                            if (n < 0) break
                            total += n
                            require(total <= MAX_BYTES) { "ملف الترجمة كبير جدًا" }
                            output.write(buffer, 0, n)
                        }
                        val rawPayload = output.toByteArray()
                        if (rawPayload.isNotEmpty()) {
                            val detectedExt = detect(rawPayload, preferArabicLegacy)
                                ?: entryExt.takeIf { it in FORMATS }
                            if (detectedExt != null && detectedExt in FORMATS) {
                                sawSupportedExtensionEntry = true
                                val normalizedPayload = runCatching {
                                    normalizeSubtitleForStorage(
                                        rawPayload,
                                        detectedExt,
                                        preferArabicLegacy
                                    )
                                }.getOrNull()
                                if (normalizedPayload != null &&
                                    isValidSubtitlePayload(normalizedPayload, detectedExt, preferArabicLegacy)
                                ) {
                                    var score = 0
                                    if (episodeMatchRegex != null && episodeMatchRegex.containsMatchIn(entryName)) {
                                        score += 100
                                    }
                                    if (preferredFormat != null && detectedExt.equals(preferredFormat, ignoreCase = true)) {
                                        score += 20
                                    } else if (detectedExt == "srt") {
                                        score += 10
                                    }
                                    val currentBest = bestCandidate
                                    if (currentBest == null ||
                                        score > currentBest.third ||
                                        (score == currentBest.third && normalizedPayload.size > currentBest.second.size)
                                    ) {
                                        bestCandidate = Triple(detectedExt, normalizedPayload, score)
                                    }
                                }
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val chosen = bestCandidate
        if (chosen != null) {
            return writeSubtitleAtomically(dir, "$outputPrefix.${chosen.first}", chosen.second)
        }
        if (sawSupportedExtensionEntry) {
            error("محتوى ملف الترجمة غير صالح: لا يحتوي على أسطر توقيت صالحة")
        }
        error("الأرشيف لا يحتوي على ملف ترجمة مدعوم")
    }


    /**
     * Manually follows bounded HTTPS redirects. The active call remains registered
     * with the coroutine until the response body is consumed, so cancellation closes
     * blocked network reads instead of waiting for the socket timeout.
     */
    @OptIn(InternalCoroutinesApi::class)
    private suspend fun <T> withDownloadResponse(
        request: Request,
        allowedDomain: String? = null,
        consume: (okhttp3.Response) -> T
    ): T {
        var current = request
        repeat(MAX_DOWNLOAD_REDIRECTS + 1) { hop ->
            val call = downloadClient.newCall(current)
            val job = currentCoroutineContext()[Job]
            val cancellationHandle = job?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true
            ) { cause ->
                if (cause != null) call.cancel()
            }
            val response = try {
                call.execute()
            } catch (failure: Throwable) {
                cancellationHandle?.dispose()
                throw failure
            }

            if (response.code !in 300..399) {
                return try {
                    currentCoroutineContext().ensureActive()
                    consume(response)
                } finally {
                    response.close()
                    cancellationHandle?.dispose()
                }
            }

            val location = response.header("Location")
            val nextUrl = location?.let { current.url.resolve(it) }
            try {
                response.close()
            } finally {
                cancellationHandle?.dispose()
            }
            require(nextUrl != null) { "أعاد مزود الترجمة تحويلاً دون وجهة صالحة" }
            require(current.method == "GET") {
                "رُفض تحويل طلب POST حفاظاً على سلامة بيانات الدخول"
            }
            require(isSafeSubtitleDownloadUrl(nextUrl.toString(), allowedDomain)) {
                if (allowedDomain == null) "رابط إعادة التوجيه ليس HTTPS آمناً"
                else "حوّل مزود الترجمة التنزيل إلى نطاق غير موثوق"
            }
            require(hop < MAX_DOWNLOAD_REDIRECTS) {
                "تجاوز مزود الترجمة الحد المسموح لإعادة التوجيه"
            }
            val builder = current.newBuilder().url(nextUrl).get()
            if (!nextUrl.host.equals(current.url.host, ignoreCase = true)) {
                builder.removeHeader("Authorization")
                    .removeHeader("Api-Key")
                    .removeHeader("x-api-key")
            }
            current = builder.build()
        }
        throw IOException("تعذر إكمال إعادة توجيه تنزيل الترجمة")
    }

    private fun openSubtitlesToken(
        apiKey: String,
        account: Pair<String, String>,
        forceRefresh: Boolean = false
    ): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh) {
            cachedOpenSubtitlesToken?.takeIf { it.expiresAtMs > now + TOKEN_REFRESH_SKEW_MS }
                ?.let { return it.value }
        }
        synchronized(tokenLock) {
            val checkedAt = System.currentTimeMillis()
            if (!forceRefresh) {
                cachedOpenSubtitlesToken
                    ?.takeIf { it.expiresAtMs > checkedAt + TOKEN_REFRESH_SKEW_MS }
                    ?.let { return it.value }
            }
            val body = JSONObject().put("username", account.first).put("password", account.second)
                .toString().toRequestBody(JSON)
            val request = Request.Builder().url("$BASE/login").header("Api-Key", apiKey)
                .header("User-Agent", AGENT).post(body).build()
            val response = execute(request)
            val token = response.optString("token").takeIf { it.isNotBlank() }
                ?: error("تعذر تسجيل الدخول إلى OpenSubtitles")
            val ttlSeconds = response.optLong("expires_in", DEFAULT_TOKEN_TTL_SECONDS)
                .coerceIn(60L, MAX_TOKEN_TTL_SECONDS)
            cachedOpenSubtitlesToken = CachedOpenSubtitlesToken(
                token,
                checkedAt + ttlSeconds * 1_000L
            )
            return token
        }
    }

    private fun isTrustedHttpsHost(uri: Uri, baseDomain: String): Boolean =
        isSafeSubtitleDownloadUrl(uri.toString(), baseDomain)

    private fun isTrustedHttpsUrl(url: String, baseDomain: String): Boolean =
        runCatching { isTrustedHttpsHost(Uri.parse(url), baseDomain) }.getOrDefault(false)

    private fun credentials(): Pair<String, Pair<String, String>> {
        val key = vault.load(KEY_SLOT)?.first ?: error("أدخل مفتاح API في نافذة البحث")
        val account = vault.load(USER_SLOT) ?: error("أدخل حساب OpenSubtitles")
        require(key.isNotBlank() && account.first.isNotBlank() && account.second.isNotBlank())
        return key to account
    }

    /** Cancellable JSON request used by the download flow, including its API-key headers. */
    private suspend fun executeDownloadApi(request: Request): JSONObject =
        withDownloadResponse(request) { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                if (response.code == 429) {
                    if (request.url.host == PUBLIC_HOST) {
                        throw SubtitleHttpException(
                            429,
                            "تم بلوغ الحد اليومي للبحث المجاني في subtitles.website. أضف مفتاح API مجانيًا من الإعدادات الاختيارية في نافذة البحث."
                        )
                    }
                    throw SubtitleHttpException(
                        429,
                        "أوقف مزود الترجمة الطلبات مؤقتًا (HTTP 429). انتظر قليلًا ثم أعد المحاولة."
                    )
                }
                if (response.code == 401 || response.code == 403) {
                    throw SubtitleHttpException(
                        response.code,
                        "مفتاح خدمة الترجمة غير صالح أو انتهت صلاحية جلسة الدخول."
                    )
                }
                val message = runCatching { JSONObject(body).optString("message") }
                    .getOrNull()?.takeIf(String::isNotBlank)
                throw SubtitleHttpException(
                    response.code,
                    message ?: "خدمة الترجمة أعادت خطأ (HTTP ${response.code})"
                )
            }
            JSONObject(body)
        }

    private fun execute(request: Request): JSONObject {
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                if (it.code == 429) {
                    if (request.url.host == PUBLIC_HOST) {
                        throw SubtitleHttpException(
                            429,
                            "تم بلوغ الحد اليومي للبحث المجاني في subtitles.website. أضف مفتاح API مجانيًا من الإعدادات الاختيارية في نافذة البحث."
                        )
                    } else {
                        error("أوقف مزود الترجمة الطلبات مؤقتًا (HTTP 429). انتظر قليلًا ثم أعد المحاولة.")
                    }
                }
                if (it.code == 401 || it.code == 403) {
                    throw SubtitleHttpException(it.code, "مفتاح خدمة الترجمة غير صالح أو انتهت صلاحية جلسة الدخول.")
                }
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                throw SubtitleHttpException(
                    it.code,
                    message?.takeIf(String::isNotBlank) ?: "خدمة الترجمة أعادت خطأ (HTTP " + it.code + ")"
                )
            }
            return JSONObject(text)
        }
    }

    /** Enforce the limit while streaming; never buffer an untrusted response of arbitrary size. */
    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            // Content providers and wrapped streams should make progress for a
            // positive-length read, but tolerate OEM/provider implementations
            // that transiently return zero instead of spinning forever.
            val count = readStreamWithProgress(input, buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_BYTES) { "حجم ملف الترجمة يتجاوز الحد المسموح" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun findReusableCachedSubtitle(dir: File, prefix: String): File? {
        val candidates = dir.listFiles()?.filter { it.name.startsWith("$prefix.") }.orEmpty()
        var valid: File? = null
        for (file in candidates) {
            if (valid == null && isValidCachedSubtitle(file)) {
                valid = file
            } else if (file.isFile) {
                runCatching { file.delete() }
            }
        }
        return valid
    }

    private fun isValidCachedSubtitle(file: File): Boolean {
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext !in FORMATS) return false
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
        if (!isValidSubtitlePayload(bytes, ext)) return false
        // Reject legacy non-UTF-8 cached files (e.g. UTF-16 or Windows-1256) so they are
        // re-saved cleanly as UTF-8 for Media3 playback compatibility.
        return runCatching {
            val offset = if (bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            ) 3 else 0
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            true
        }.getOrDefault(false)
    }

    private fun writeSubtitleAtomically(dir: File, fileName: String, bytes: ByteArray): File {
        require(bytes.size in 1..MAX_BYTES) { "حجم ملف الترجمة غير صالح" }
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(isValidSubtitlePayload(bytes, ext)) { "محتوى ملف الترجمة غير صالح" }
        val target = File(dir, fileName)
        val temporary = File.createTempFile("subtitle-", ".part", dir)
        try {
            writeFileAtomically(
                temporary,
                target,
                bytes,
                "تعذر اعتماد ملف الترجمة بعد التنزيل دون فقدان النسخة السابقة."
            )
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun ByteArray.isZip() = size > 3 && this[0] == 0x50.toByte() && this[1] == 0x4b.toByte()

    private fun unwrapGzipIfNeeded(bytes: ByteArray): ByteArray {
        val isGzip = bytes.size > 3 &&
            bytes[0] == 0x1f.toByte() &&
            bytes[1] == 0x8b.toByte() &&
            bytes[2] == 0x08.toByte()
        if (!isGzip) return bytes
        return runCatching {
            GZIPInputStream(bytes.inputStream()).use(::readBounded)
        }.getOrDefault(bytes)
    }

    private fun detect(bytes: ByteArray, preferArabicLegacy: Boolean = false): String? =
        detectSubtitleFormat(bytes, preferArabicLegacy)

    private companion object {
        const val BASE = "https://api.opensubtitles.com/api/v1"
        const val PUBLIC_HOST = "subtitles.website"
        const val PUBLIC_BASE = "https://subtitles.website/strapi"
        const val PUBLIC_API = "$PUBLIC_BASE/api"
        const val PUBLIC_PROVIDER = "subtitleSearchEngine"
        const val WEBSITE_KEY_SLOT = "subtitles_website_api_key"
        const val SUBDL_KEY_SLOT = "subdl_api_key"
        const val SUBDL_API = "https://api.subdl.com/api/v1/subtitles"
        const val SUBDL_V2_FILES_API = "https://api.subdl.com/api/v2/files/search"
        const val SUBDL_V2_MOVIES_API = "https://api.subdl.com/api/v2/movies/search"
        const val SUBDL_PROVIDER = "subdl"
        const val SUBF2M_PROVIDER = "subf2m"
        const val AGENT = "MalekPlayer v2.0.0"
        const val KEY_SLOT = "opensubtitles_api_key"
        const val USER_SLOT = "opensubtitles_account"
        const val MAX_BYTES = 8 * 1024 * 1024
        const val DEFAULT_TOKEN_TTL_SECONDS = 900L
        const val MAX_TOKEN_TTL_SECONDS = 3_600L
        const val TOKEN_REFRESH_SKEW_MS = 30_000L
        const val MAX_DOWNLOAD_REDIRECTS = 5
        val JSON = "application/json; charset=utf-8".toMediaType()
        val FORMATS = setOf("srt", "ass", "ssa", "vtt")
    }
}
