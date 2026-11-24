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
    val episodeNumber: Int? = null
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

/** Strict content/extension check shared by the cache writer and unit tests. */
internal fun isValidSubtitlePayload(bytes: ByteArray, extension: String): Boolean {
    if (extension !in setOf("srt", "ass", "ssa", "vtt") ||
        bytes.isEmpty() || bytes.size > 8 * 1024 * 1024
    ) return false

    val decodedText = runCatching { SrtSubtitleParser.decode(bytes) }.getOrNull() ?: return false
    val text = decodedText.trimStart('\uFEFF', ' ', '\t', '\n', '\r')
    val timestamp = """(?:\d{1,2}:)?\d{1,2}:\d{2}[,.]\d{3}"""
    val arrowCue = Regex(
        """(?m)^\s*$timestamp\s+-->\s*$timestamp(?:\s+.*)?$"""
    ).containsMatchIn(text)
    val assCue = text.contains("[Script Info]", ignoreCase = true) &&
        text.contains("[Events]", ignoreCase = true) &&
        text.lineSequence().any { it.trimStart().startsWith("Dialogue:", ignoreCase = true) }
    val detected = when {
        text.startsWith("WEBVTT", ignoreCase = true) && arrowCue -> "vtt"
        assCue -> "ass"
        arrowCue -> "srt"
        else -> return false
    }
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

    fun saveProviderApiKeys(subdlKey: String, websiteKey: String) {
        if (subdlKey.isNotBlank()) vault.save(SUBDL_KEY_SLOT, subdlKey.trim(), "api-key")
        if (websiteKey.isNotBlank()) vault.save(WEBSITE_KEY_SLOT, websiteKey.trim(), "api-key")
        automaticSearchCache.clear()
        publicSearchCache.clear()
    }

    /** Stores only the public Worker URL. The Wyzie API key remains a server-side secret. */
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
        val providerTitle = cleaned.title + (cleaned.year?.let { " $it" } ?: "")
        val queryKey = buildString {
            append(providerTitle.trim().lowercase(Locale.ROOT))
            append("|s").append(resolvedSeason ?: 0).append("e").append(resolvedEpisode ?: 0)
            append("|").append(mediaUri?.toString().orEmpty())
        }

        // Share one network fan-out for simultaneous identical searches.
        // Each caller applies its own language filter to the combined results.
        val candidate = FutureTask {
            searchAutomaticUncached(queryKey, cleaned, providerTitle, resolvedSeason, resolvedEpisode, mediaUri)
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
        providerTitle: String,
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
        val imdbId = if (subdlKey != null) runCatching {
            searchSubDlTvIdentity(
                cleaned.title, cleaned.year, subdlKey,
                if (season != null || episode != null) "tv" else "movie"
            )
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
        dir.listFiles()?.firstOrNull {
            it.name.startsWith("$cachePrefix.") && isValidCachedSubtitle(it)
        }?.let { return Uri.fromFile(it) }

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
            .apply {
                val bearer = vault.load(WEBSITE_KEY_SLOT)?.first
                if (!bearer.isNullOrBlank()) header("Authorization", "Bearer $bearer")
            }.get().build()
        return withDownloadResponse(downloadRequest, PUBLIC_HOST) { response ->
            require(isTrustedHttpsUrl(response.request.url.toString(), PUBLIC_HOST)) {
                "حوّل مزود الترجمة التنزيل إلى نطاق غير موثوق"
            }
            if (!response.isSuccessful) error("تعذر تنزيل الترجمة (HTTP ${response.code})")
            val bytes = response.body?.byteStream()?.use(::readBounded) ?: error("ملف الترجمة فارغ")
            require(bytes.isNotEmpty()) { "ملف الترجمة فارغ" }
            val target = if (bytes.isZip()) {
                extract(bytes, dir, 0L, cachePrefix)
            } else {
                val ext = detect(bytes) ?: subtitle.format.takeIf { it in FORMATS }
                    ?: error("صيغة ملف الترجمة غير مدعومة")
                writeSubtitleAtomically(dir, "$cachePrefix.$ext", bytes)
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
        for (index in 0 until results.length()) {
            val item = results.optJSONObject(index) ?: continue
            val id = item.optString("imdb_id").takeIf { it.matches(Regex("tt\\d{5,12}")) }
            if (id != null) return id
        }
        return null
    }

    private suspend fun downloadSubF2m(subtitle: OnlineSubtitle): Uri {
        val detailUrl = subtitle.downloadUrl ?: error("رابط صفحة SubF2M غير متوفر؛ أعد البحث")
        val directory = File(app.cacheDir, "online-subtitles").apply { mkdirs() }
        val prefix = "subtitle-subf2m-${subtitleCacheToken(subtitle.sourceId)}"
        directory.listFiles()?.firstOrNull {
            it.name.startsWith("$prefix.") && isValidCachedSubtitle(it)
        }?.let { return Uri.fromFile(it) }
        val bytes = subf2mProvider.download(detailUrl)
        val target = if (bytes.isZip()) {
            extract(bytes, directory, 0L, prefix)
        } else {
            val extension = detect(bytes) ?: error("صيغة ملف ترجمة SubF2M غير مدعومة")
            writeSubtitleAtomically(directory, "$prefix.$extension", bytes)
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
                    cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME))
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
            .appendQueryParameter("episode_scope", "exact")
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
                    fallbackTitle = displayName
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
                            fallbackTitle = title
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
                        fallbackTitle = title
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
        directory.listFiles()?.firstOrNull {
            it.name.startsWith("$prefix.") && isValidCachedSubtitle(it)
        }?.let { return Uri.fromFile(it) }

        val downloadRequest = Request.Builder().url(downloadUri.toString())
            .apply {
                val key = vault.load(SUBDL_KEY_SLOT)?.first
                if (!key.isNullOrBlank()) header("x-api-key", key)
            }.get().build()
        return withDownloadResponse(downloadRequest, "subdl.com") { response ->
            require(isTrustedHttpsUrl(response.request.url.toString(), "subdl.com")) {
                "حوّل SubDL التنزيل إلى نطاق غير موثوق"
            }
            if (!response.isSuccessful) error("تعذر تنزيل ترجمة SubDL (HTTP ${response.code})")
            val bytes = response.body?.byteStream()?.use(::readBounded) ?: error("ملف الترجمة فارغ")
            val target = if (bytes.isZip()) extract(bytes, directory, 0L, prefix)
            else {
                val ext = detect(bytes) ?: subtitle.format
                writeSubtitleAtomically(directory, "$prefix.$ext", bytes)
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
        dir.listFiles()?.firstOrNull {
            it.name.startsWith("subtitle-$fileId.") && isValidCachedSubtitle(it)
        }?.let { return Uri.fromFile(it) }
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
            val bytes = response.body?.byteStream()?.use(::readBounded) ?: error("ملف الترجمة فارغ")
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "حجم ملف الترجمة غير صالح" }
            val target = if (bytes.isZip()) extract(bytes, dir, fileId) else {
                val ext = detect(bytes) ?: error("صيغة ملف الترجمة غير مدعومة")
                writeSubtitleAtomically(dir, "subtitle-$fileId.$ext", bytes)
            }
            require(target.length() in 1..MAX_BYTES.toLong())
            Uri.fromFile(target)
        }
    }

    private fun extract(bytes: ByteArray, dir: File, id: Long, outputPrefix: String = "subtitle-$id"): File {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (!entry.isDirectory && ext in FORMATS) {
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
                    val payload = output.toByteArray()
                    require(isValidSubtitlePayload(payload, ext)) { "محتوى ملف الترجمة غير صالح" }
                    return writeSubtitleAtomically(dir, "$outputPrefix.$ext", payload)
                }
                zip.closeEntry(); entry = zip.nextEntry
            }
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

    private fun isValidCachedSubtitle(file: File): Boolean {
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return false
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext !in FORMATS) return false
        return runCatching { isValidSubtitlePayload(file.readBytes(), ext) }.getOrDefault(false)
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
    private fun detect(bytes: ByteArray): String? {
        val decodedText = runCatching { SrtSubtitleParser.decode(bytes) }.getOrNull() ?: return null
        val text = decodedText.trimStart('\uFEFF', ' ', '\n', '\r')
        return when {
            text.startsWith("WEBVTT", true) -> "vtt"
            text.contains("-->") -> "srt"
            text.startsWith("[Script Info]", true) || text.contains("[Events]", true) -> "ass"
            else -> null
        }
    }

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
