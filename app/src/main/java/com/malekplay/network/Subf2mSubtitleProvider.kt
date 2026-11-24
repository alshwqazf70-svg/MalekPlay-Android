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
            add(title.trim())
            if (':' in title) add(title.substringBefore(':').trim())
        }.filter(String::isNotBlank).distinctBy { it.lowercase(Locale.ROOT) }

        val candidates = linkedMapOf<String, String>()
        for (query in queries) {
            val searchUrl = Uri.parse("$BASE/subtitles/searchbytitle").buildUpon()
                .appendQueryParameter("query", query.take(150))
                .appendQueryParameter("l", "")
                .build().toString()
            val html = getText(searchUrl)
            SEARCH_LINK.findAll(html).forEach { match ->
                val path = decodeHtml(match.groupValues[1])
                if (!path.startsWith("/subtitles/") || path.contains("..")) return@forEach
                val resultTitle = decodeHtml(stripTags(match.groupValues[2]))
                if (matchesTitle(title, resultTitle, year, season)) candidates.putIfAbsent(path, resultTitle)
            }
            if (candidates.isNotEmpty()) break
        }

        val results = mutableListOf<OnlineSubtitle>()
        val languages = listOf("arabic" to "ar", "english" to "en")
        for ((path, resultTitle) in candidates.entries.take(MAX_TITLE_RESULTS)) {
            for ((languagePath, languageCode) in languages) {
                val pageUrl = "$BASE$path/$languagePath"
                val page = runCatching { getText(pageUrl, BASE) }.getOrNull() ?: continue
                val pageImdb = IMDB.find(page)?.groupValues?.get(1)
                if (!imdbId.isNullOrBlank() && pageImdb != null && pageImdb != imdbId) continue
                val pageTitle = PAGE_TITLE.find(page)?.groupValues?.get(1)
                    ?.let { decodeHtml(stripTags(it)).removePrefix("Subtitles for ").trim() }
                if (!pageTitle.isNullOrBlank() &&
                    !similarTitle(title, pageTitle) &&
                    pageImdb != imdbId
                ) continue

                val starts = ITEM_START.findAll(page).toList()
                for (index in starts.indices) {
                    val start = starts[index].range.first
                    val end = if (index + 1 < starts.size) starts[index + 1].range.first else page.length
                    val block = page.substring(start, end)
                    val release = extractRelease(block)
                    if (release.isBlank()) continue
                    if (season != null && episode != null && !isExactEpisode(release, season, episode)) continue
                    val link = DOWNLOAD_LINK.find(block)?.groupValues?.get(1)
                        ?.let(::decodeHtml) ?: continue
                    val detailUrl = resolveSiteUrl(link) ?: continue
                    val id = detailUrl.substringBefore('?').trimEnd('/').substringAfterLast('/')
                    if (!id.matches(Regex("[0-9]{1,16}"))) continue
                    val identity = "$languageCode:$id"
                    if (results.any { it.sourceId == identity }) continue
                    results += OnlineSubtitle(
                        fileId = 0L,
                        language = languageCode,
                        release = release.ifBlank { resultTitle },
                        downloads = 0,
                        format = "srt",
                        sourceId = identity,
                        provider = PROVIDER,
                        downloadUrl = detailUrl,
                        seasonNumber = season,
                        episodeNumber = episode
                    )
                    if (results.size >= MAX_RESULTS) return results
                }
            }
            val languagesFound = results.map { it.language }.toSet()
            if (languagesFound.containsAll(setOf("ar", "en"))) return results
        }
        return results
    }

    fun download(detailUrl: String): ByteArray {
        val detail = resolveSiteUrl(detailUrl) ?: error("رابط صفحة الترجمة غير صالح")
        val page = getText(detail, BASE)
        val rawDownload = DOWNLOAD_BUTTON.find(page)?.groupValues?.get(1)
            ?.let(::decodeHtml) ?: error("لم يعثر SubF2M على رابط تنزيل")
        val initial = resolveSiteUrl(rawDownload, detail)
            ?: error("رابط تنزيل SubF2M غير آمن")
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
            .header("Accept", "text/html,application/xhtml+xml")
            .apply { referer?.let { header("Referer", it) } }
            .get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("تعذر الاتصال بـSubF2M (HTTP ${response.code})")
            val body = response.body?.string().orEmpty()
            require(body.length <= MAX_HTML_CHARS) { "صفحة SubF2M أكبر من الحد المسموح" }
            return body
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
        val comment = COMMENT.find(block)?.groupValues?.get(1)
            ?.let { decodeHtml(stripTags(it)) }.orEmpty()
        return (release + listOf(comment)).filter(String::isNotBlank).joinToString(" · ").take(500)
    }

    private fun matchesTitle(wanted: String, candidate: String, year: Int?, season: Int?): Boolean {
        if (year != null) {
            val candidateYear = YEAR.find(candidate)?.groupValues?.get(1)?.toIntOrNull()
            if (candidateYear != null && candidateYear != year) return false
        }
        if (season != null) {
            val parsed = seasonNumber(candidate)
            if (parsed != null && parsed != season) return false
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
        val match = Regex("(?i)(?:season|series|s)\\s*0*(\\d{1,2})").find(value)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    internal fun isExactEpisode(release: String, season: Int, episode: Int): Boolean {
        val patterns = listOf(
            Regex("(?i)\\bs0*\\$season\\s*e0*\\$episode\\b"),
            Regex("(?i)\\b0*\\$season\\s*x\\s*0*\\$episode\\b"),
            Regex("(?i)\\bseason\\s*0*\\$season\\s*[- ]\\s*0*\\$episode\\b")
        )
        return patterns.any { it.containsMatchIn(release) }
    }

    private fun stripTags(value: String): String = TAGS.replace(value, " ")
    private fun decodeHtml(value: String): String =
        Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val head = bytes.take(256).toByteArray().toString(Charsets.UTF_8).trimStart()
        return head.startsWith("<!DOCTYPE", true) || head.startsWith("<html", true)
    }

    companion object {
        const val PROVIDER = "subf2m"
        private const val HOST = "subf2m.co"
        private const val CDN_HOST = "isubcdn.com"
        private const val BASE = "https://subf2m.co"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 MalekPlayer"
        private const val MAX_HTML_CHARS = 3 * 1024 * 1024
        private const val MAX_BYTES = 8 * 1024 * 1024
        private const val MAX_TITLE_RESULTS = 3
        private const val MAX_RESULTS = 40
        private const val MAX_REDIRECTS = 5
        private val TAGS = Regex("(?is)<[^>]+>")
        private val YEAR = Regex("\\((\\d{4})\\)")
        private val IMDB = Regex("(?i)imdb\\.com/title/(tt\\d+)")
        private val PAGE_TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
        private val SEARCH_LINK = Regex("(?is)<div[^>]*class=['\"][^'\"]*\\btitle\\b[^'\"]*['\"][^>]*>\\s*<a[^>]*href=['\"](/subtitles/[^'\"]+)['\"][^>]*>(.*?)</a>")
        private val ITEM_START = Regex("(?is)<li\\b[^>]*class=['\"][^'\"]*\\bitem\\b[^'\"]*['\"][^>]*>")
        private val SCROLL_LIST = Regex("(?is)<ul[^>]*class=['\"][^'\"]*\\bscrolllist\\b[^'\"]*['\"][^>]*>(.*?)</ul>")
        private val INNER_LI = Regex("(?is)<li[^>]*>(.*?)</li>")
        private val COMMENT = Regex("(?is)<div[^>]*class=['\"][^'\"]*\\bcomment-col\\b[^'\"]*['\"][^>]*>.*?<p[^>]*>(.*?)</p>")
        private val DOWNLOAD_LINK = Regex("(?is)<a\\b(?=[^>]*class=['\"][^'\"]*\\bdownload\\b)(?=[^>]*href=['\"]([^'\"]+)['\"])[^>]*>")
        private val DOWNLOAD_BUTTON = Regex("(?is)<a\\b(?=[^>]*id=['\"]downloadButton['\"])(?=[^>]*href=['\"]([^'\"]+)['\"])[^>]*>")
    }
}
