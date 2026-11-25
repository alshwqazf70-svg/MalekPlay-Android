package com.malekplay.network

import android.net.Uri
import android.text.Html
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Direct SubF2M HTML provider. The site has no public JSON API, so parsing is deliberately
 * bounded and accepts only the site's known page paths and supported subtitle rows.
 */
internal class Subf2mSubtitleProvider {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .build()

    fun search(
        title: String,
        year: Int?,
        season: Int?,
        episode: Int?,
        imdbId: String?
    ): List<OnlineSubtitle> {
        require(title.isNotBlank()) { "أدخل اسم الفيلم أو المسلسل" }
        require((season == null) == (episode == null)) {
            "يجب تحديد الموسم والحلقة معاً للبحث عن ترجمة المسلسل"
        }
        val queries = buildList {
            if (season != null) add("${title.trim()} - ${seasonOrdinal(season)} Season")
            add(title.trim())
            if (!imdbId.isNullOrBlank()) add(imdbId)
            if (':' in title) add(title.substringBefore(':').trim())
        }.filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }

        val candidates = linkedMapOf<String, String>()
        for (query in queries) {
            val searchUrl = Uri.parse("$BASE/subtitles/searchbytitle").buildUpon()
                .appendQueryParameter("query", query.take(150))
                .appendQueryParameter("l", "")
                .build().toString()
            val html = getText(searchUrl)
            parseSearchResultCandidates(html, title, year, season).forEach { (path, resultTitle) ->
                if (candidates.size < MAX_CANDIDATE_PAGES) {
                    if (!candidates.containsKey(path)) candidates[path] = resultTitle
                }
            }
            // Continue through alternate title/IMDb queries even after a fuzzy hit.
            // The existing strict title matcher and hard candidate cap remain in force.
        }

        val results = mutableListOf<OnlineSubtitle>()
        val languages = listOf("arabic" to "ar", "english" to "en")
        for ((path, resultTitle) in candidates.entries.take(MAX_TITLE_RESULTS)) {
            for ((languagePath, languageCode) in languages) {
                val pageUrl = "$BASE${path}/$languagePath"
                val page = runCatching { getText(pageUrl, BASE) }.getOrNull() ?: continue
                val parsedPage = parseSubtitlePage(
                    page, languageCode, resultTitle, season, episode, imdbId
                )
                parsedPage.forEach { candidate ->
                    if (results.none { it.sourceId == candidate.sourceId }) results += candidate
                }
                if (results.size >= MAX_RESULTS) return results
            }
            val languagesFound = results.map { it.language }.toSet()
            if (languagesFound.containsAll(setOf("ar", "en"))) return results
        }
        return results
    }

    internal fun parseSearchResultCandidates(
        html: String,
        title: String,
        year: Int?,
        season: Int?
    ): List<Pair<String, String>> {
        // The live SubF2M search page renders each hit as:
        // li > div.title > a[href="/subtitles/..."].
        return TITLE_ANCHOR.findAll(html).mapNotNull { match ->
            val rawPath = decodeHtml(match.groupValues[1])
            if (!rawPath.startsWith("/subtitles/") || rawPath.contains("..")) return@mapNotNull null
            val resultTitle = decodeHtml(stripTags(match.groupValues[2]))
            if (matchesTitle(title, resultTitle, year, season)) {
                rawPath.trimEnd('/') to resultTitle
            } else null
        }.distinctBy { it.first }.toList()
    }

    internal fun resolveDownloadPageLink(page: String, detailUrl: String): String? {
        // SubF2M exposes the final action as #downloadButton; retain a path fallback for older pages.
        val raw = DOWNLOAD_BUTTON.find(page)?.groupValues?.get(1)?.let(::decodeHtml)
            ?: DOWNLOAD_HREF.find(page)?.groupValues?.get(1)?.let(::decodeHtml)
            ?: return null
        return resolveSiteUrl(raw, detailUrl)
    }

    private fun seasonOrdinal(season: Int): String =
        SEASON_ORDINALS.getOrNull(season) ?: "Season $season"

    internal fun parseSubtitlePage(
        page: String,
        languageCode: String,
        resultTitle: String,
        season: Int?,
        episode: Int?,
        imdbId: String?
    ): List<OnlineSubtitle> {
        val pageImdb = IMDB.find(page)?.groupValues?.get(1)
        if (!imdbId.isNullOrBlank() && pageImdb != null && pageImdb != imdbId) return emptyList()
        val pageTitle = PAGE_TITLE.find(page)?.groupValues?.get(1)
            ?.let { decodeHtml(stripTags(it)).removePrefix("Subtitles for ").trim() }
        val imdbIdentityConfirmed = !imdbId.isNullOrBlank() && pageImdb == imdbId
        if (!pageTitle.isNullOrBlank() &&
            !similarTitle(resultTitle.substringBefore(" ("), pageTitle) &&
            !imdbIdentityConfirmed
        ) return emptyList()

        val parsed = mutableListOf<OnlineSubtitle>()
        val starts = ITEM_START.findAll(page).toList()
        for (index in starts.indices) {
            val start = starts[index].range.first
            val end = if (index + 1 < starts.size) starts[index + 1].range.first else page.length
            val block = page.substring(start, end)
            val release = extractRelease(block)
            if (release.isBlank()) continue
            if (season != null && episode != null && !isExactEpisode(release, season, episode)) continue
            val releaseInfo = listOf(release, extractComment(block))
                .filter(String::isNotBlank).joinToString(" · ").take(500)
            val link = DOWNLOAD_LINK.find(block)?.groupValues?.get(1)
                ?.let(::decodeHtml) ?: continue
            val detailUrl = resolveSiteUrl(link) ?: continue
            val id = detailUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')
            if (!id.matches(Regex("[0-9]{1,16}"))) continue
            parsed += OnlineSubtitle(
                fileId = 0L,
                language = languageCode,
                release = releaseInfo.ifBlank { resultTitle },
                downloads = 0,
                format = "srt",
                sourceId = "$languageCode:$id",
                provider = PROVIDER,
                downloadUrl = detailUrl,
                seasonNumber = season,
                episodeNumber = episode
            )
            if (parsed.size >= MAX_RESULTS) break
        }
        return parsed.distinctBy { it.sourceId }
    }

    fun download(detailUrl: String): ByteArray {
        val detail = resolveSiteUrl(detailUrl) ?: error("رابط صفحة الترجمة غير صالح")
        val page = getText(detail, BASE)
        val initial = resolveDownloadPageLink(page, detail)
            ?: error("لم يعثر SubF2M على رابط تنزيل آمن")
        return fetchDownload(initial, detail)
    }

    private fun fetchDownload(initialUrl: String, referer: String): ByteArray {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { hop ->
            val uri = Uri.parse(current)
            require(isAllowedHost(uri.host)) { "نطاق تنزيل SubF2M غير موثوق" }
            val request = Request.Builder().url(current)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/zip,application/octet-stream,text/plain,*/*")
                .header("Referer", referer)
                .get().build()
            client.newCall(request).execute().use { response ->
                if (response.code in 300..399) {
                    val location = response.header("Location")
                        ?: error("رابط تحويل تنزيل SubF2M غير مكتمل")
                    val next = response.request.url.resolve(location)
                        ?: error("رابط تحويل تنزيل SubF2M غير صالح")
                    current = next.toString()
                    require(isAllowedHost(next.host)) { "حوّل SubF2M التنزيل إلى نطاق غير موثوق" }
                    require(hop < MAX_REDIRECTS) { "تجاوز تنزيل SubF2M عدد التحويلات المسموح" }
                    return@use
                }
                if (!response.isSuccessful) throw IOException("تعذر تنزيل ترجمة SubF2M (HTTP ${response.code})")
                val body = response.body ?: error("ملف ترجمة SubF2M فارغ")
                if (body.contentLength() > MAX_BYTES) error("حجم ملف الترجمة يتجاوز الحد المسموح")
                val bytes = body.byteStream().use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BYTES) { "حجم ملف الترجمة يتجاوز الحد المسموح" }
                        out.write(buffer, 0, count)
                    }
                    out.toByteArray()
                }
                require(bytes.isNotEmpty()) { "ملف ترجمة SubF2M فارغ" }
                val contentType = response.header("Content-Type").orEmpty()
                require(!contentType.contains("text/html", true) && !looksLikeHtml(bytes)) {
                    "أعاد SubF2M صفحة ويب بدلاً من ملف الترجمة"
                }
                return bytes
            }
        }
        error("تعذر تنزيل ترجمة SubF2M")
    }

    private fun getText(url: String, referer: String? = null): String {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" && uri.host == HOST && uri.userInfo == null) {
            "رابط بحث SubF2M غير آمن"
        }
        val request = Request.Builder().url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Upgrade-Insecure-Requests", "1")
            .apply { referer?.let { header("Referer", it) } }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("تعذر الاتصال بـSubF2M (HTTP ${response.code})")
            val responseBody = response.body ?: return ""
            if (responseBody.contentLength() > MAX_HTML_BYTES) {
                error("صفحة SubF2M أكبر من الحد المسموح")
            }
            val bytes = responseBody.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_HTML_BYTES) { "صفحة SubF2M أكبر من الحد المسموح" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private fun resolveSiteUrl(raw: String, base: String = BASE): String? = runCatching {
        val resolved = java.net.URI(base).resolve(raw)
        if (!resolved.scheme.equals("https", true) || !isAllowedHost(resolved.host) ||
            resolved.rawUserInfo != null || resolved.port !in listOf(-1, 443)
        ) null else resolved.toString()
    }.getOrNull()

    private fun isAllowedHost(host: String?): Boolean =
        host.equals(HOST, true) || host.equals(CDN_HOST, true) ||
            host?.endsWith(".$CDN_HOST", true) == true

    private fun extractRelease(block: String): String {
        val release = SCROLL_LIST.findAll(block).flatMap { list ->
            INNER_LI.findAll(list.groupValues[1]).map { decodeHtml(stripTags(it.groupValues[1])) }
        }.filter(String::isNotBlank).toList()
        return release.joinToString(" · ").take(500)
    }

    private fun extractComment(block: String): String =
        COMMENT.find(block)?.groupValues?.get(1)
            ?.let { decodeHtml(stripTags(it)) }.orEmpty()

    private fun matchesTitle(wanted: String, candidate: String, year: Int?, season: Int?): Boolean {
        if (year != null) {
            val candidateYear = YEAR.find(candidate)?.groupValues?.get(1)?.toIntOrNull()
            if (candidateYear != null && candidateYear != year) return false
        }
        if (season != null) {
            val parsed = seasonNumber(candidate)
            if (parsed != null && parsed != season) return false
            val ordinal = SEASON_ORDINALS.getOrNull(season)
            if (ordinal != null && Regex("(?i)\\b${ordinal}\\s+season\\b").containsMatchIn(candidate).not() &&
                parsed != null && parsed != season
            ) return false
        }
        return similarTitle(wanted, candidate)
    }

    private fun similarTitle(wanted: String, candidate: String): Boolean {
        val a = normalizeTitle(wanted)
        val b = normalizeTitle(candidate)
        val tokens = a.split(' ').filter { it.length > 1 && it.toIntOrNull() == null }
        if (tokens.isEmpty()) return false
        val matched = tokens.count { token -> b.split(' ').contains(token) }
        return matched >= tokens.size || matched.toFloat() / tokens.size >= 0.75f
    }

    private fun normalizeTitle(value: String): String = decodeHtml(stripTags(value))
        .lowercase(Locale.ROOT)
        .replace(Regex("\\(\\d{4}\\)"), " ")
        .replace(Regex("\\b(?:first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\\s+(?:season|series)\\b"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun seasonNumber(value: String): Int? {
        val numeric = Regex("(?i)(?:season|series|s)\\s*0*(\\d{1,2})").find(value)
            ?.groupValues?.get(1)?.toIntOrNull()
        if (numeric != null) return numeric
        return SEASON_ORDINALS.indexOfFirst { ordinal ->
            ordinal.isNotBlank() && Regex("(?i)\\b${ordinal}\\s+season\\b").containsMatchIn(value)
        }.takeIf { it > 0 }
    }

    internal fun isExactEpisode(release: String, season: Int, episode: Int): Boolean {
        val patterns = listOf(
            Regex("(?i)\\bs0*$season\\s*e0*$episode\\b"),
            Regex("(?i)\\b0*$season\\s*x\\s*0*$episode\\b"),
            Regex("(?i)\\bseason\\s*0*$season\\s*[- ]\\s*0*$episode\\b")
        )
        return patterns.any { it.containsMatchIn(release) } &&
            !SubtitleResultPolicy.isMultiEpisodeRange(release, season)
    }

    private fun stripTags(value: String): String = TAGS.replace(value, " ")
    @Suppress("DEPRECATION")
    private fun decodeHtml(value: String): String =
        Html.fromHtml(value).toString().replace(Regex("\\s+"), " ").trim()

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = bytes.take(2048).toByteArray().toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\n', '\r')
            .lowercase(Locale.ROOT)
        if (head.startsWith("<!doctype") ||
            head.startsWith("<html") ||
            head.startsWith("<head") ||
            head.startsWith("<body")
        ) return true
        val looksChallenge = head.contains("just a moment...") ||
            head.contains("cf-browser-verification")
        return looksChallenge && !head.contains("-->") && !head.contains("dialogue:")
    }

    companion object {
        const val PROVIDER = "subf2m"
        private const val HOST = "subf2m.co"
        private const val CDN_HOST = "isubcdn.com"
        private const val BASE = "https://subf2m.co"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 MalekPlayer"
        private const val MAX_HTML_BYTES = 3 * 1024 * 1024
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_TITLE_RESULTS = 3
        private const val MAX_CANDIDATE_PAGES = 12
        private const val MAX_RESULTS = 40
        private const val MAX_REDIRECTS = 5
        private val SEASON_ORDINALS = listOf(
            "", "First", "Second", "Third", "Fourth", "Fifth", "Sixth", "Seventh",
            "Eighth", "Ninth", "Tenth", "Eleventh", "Twelfth", "Thirteenth",
            "Fourteenth", "Fifteenth", "Sixteenth", "Seventeenth", "Eighteenth",
            "Nineteenth", "Twentieth"
        )
        private val TAGS = Regex("(?is)<[^>]+>")
        private val YEAR = Regex("\\((\\d{4})\\)")
        private val IMDB = Regex("(?i)imdb\\.com/title/(tt\\d+)")
        private val PAGE_TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
        private val TITLE_ANCHOR = Regex("""(?is)<div\b[^>]*class=['"]title['"][^>]*>\s*<a\b[^>]*href=['"](/subtitles/[^'"]+)['"][^>]*>(.*?)</a>""")
        private val ITEM_START = Regex("(?is)<li\\b[^>]*class=['\"][^'\"]*\\bitem\\b[^'\"]*['\"][^>]*>")
        private val SCROLL_LIST = Regex("(?is)<ul[^>]*class=['\"][^'\"]*\\bscrolllist\\b[^'\"]*['\"][^>]*>(.*?)</ul>")
        private val INNER_LI = Regex("(?is)<li[^>]*>(.*?)</li>")
        private val COMMENT = Regex("(?is)<div[^>]*class=['\"][^'\"]*\\bcomment-col\\b[^'\"]*['\"][^>]*>.*?<p[^>]*>(.*?)</p>")
        private val DOWNLOAD_LINK = Regex("""(?is)<a\b(?=[^>]*class=['"][^'"]*\bdownload\b)(?=[^>]*href=['"]([^'"]+)['"])[^>]*>""")
        private val DOWNLOAD_BUTTON = Regex("""(?is)<a\b(?=[^>]*id=['"]downloadButton['"])(?=[^>]*href=['"]([^'"]+)['"])[^>]*>""")
        private val DOWNLOAD_HREF = Regex("""(?is)<a\b(?=[^>]*href=['"]([^'"]*/download[^'"]*)['"])[^>]*>""")
    }
}
