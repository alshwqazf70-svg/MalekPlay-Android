package com.malekplay.network

import android.content.Context
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class OnlineSubtitle(
    val fileId: Long,
    val language: String,
    val release: String,
    val downloads: Int,
    val format: String,
    val sourceId: String = fileId.toString(),
    val provider: String = "opensubtitles"
)

class OnlineSubtitleRepository(context: Context, private val vault: NetworkCredentialVault) {
    private val app = context.applicationContext
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()

    fun saveCredentials(apiKey: String, username: String, password: String) {
        require(apiKey.isNotBlank() && username.isNotBlank() && password.isNotBlank())
        vault.save(KEY_SLOT, apiKey.trim(), "")
        vault.save(USER_SLOT, username.trim(), password)
    }

    fun hasCredentials(): Boolean = runCatching {
        val key = vault.load(KEY_SLOT)?.first
        val user = vault.load(USER_SLOT)
        !key.isNullOrBlank() && !user?.first.isNullOrBlank() && !user?.second.isNullOrBlank()
    }.getOrDefault(false)

    /**
     * Searches a public guest endpoint first. No account, API key, or setup is required
     * for the user. The provider documents a small daily guest quota, so failures are
     * surfaced cleanly and can be retried later.
     */
    fun searchAutomatic(title: String, language: String, season: Int?, episode: Int?): List<OnlineSubtitle> {
        val publicAttempt = runCatching { searchPublic(title, language, season, episode) }
        val accountAttempt = if (hasCredentials()) {
            runCatching { search(title, language, season, episode) }
        } else null
        val publicResults = publicAttempt.getOrDefault(emptyList())
        val accountResults = accountAttempt?.getOrDefault(emptyList()).orEmpty()
        if (publicAttempt.isFailure && accountAttempt?.isFailure != false) {
            throw publicAttempt.exceptionOrNull() ?: IllegalStateException("تعذر البحث عن الترجمة")
        }
        return (publicResults + accountResults)
            .distinctBy { it.provider + ":" + it.sourceId }
            .take(40)
    }

    private fun searchPublic(
        title: String,
        language: String,
        season: Int?,
        episode: Int?
    ): List<OnlineSubtitle> {
        require(title.isNotBlank()) { "أدخل اسم الفيلم أو المسلسل" }
        val episodeSuffix = if (season != null && episode != null) {
            " S%02dE%02d".format(Locale.ROOT, season, episode)
        } else ""
        val query = (title.trim().take(150) + episodeSuffix).trim()
        val languages = language.split(',').map { it.trim().lowercase(Locale.ROOT) }
            .filter { it in setOf("ar", "en") }.ifEmpty { listOf("ar", "en") }
        return languages.flatMap { lang ->
            val url = Uri.parse("$PUBLIC_API/search/subtitles").buildUpon()
                .appendQueryParameter("title", query)
                .appendQueryParameter("language", lang)
                .build().toString()
            val request = Request.Builder().url(url)
                .header("User-Agent", AGENT)
                .header("Accept", "application/json")
                .get().build()
            val data = execute(request).optJSONArray("data") ?: return@flatMap emptyList()
            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
                    val format = item.optString("format").lowercase(Locale.ROOT)
                    if (format !in FORMATS) continue
                    val languageValue = item.opt("language")
                    val languageCode = when (languageValue) {
                        is JSONObject -> languageValue.optString("code", lang)
                        null -> lang
                        else -> languageValue.toString()
                    }
                    add(
                        OnlineSubtitle(
                            fileId = 0L,
                            language = languageCode,
                            release = item.optString("release").ifBlank { item.optString("film_title", "Subtitle") },
                            downloads = item.optInt("downloads"),
                            format = format,
                            sourceId = id,
                            provider = PUBLIC_PROVIDER
                        )
                    )
                }
            }
        }.distinctBy { it.sourceId }.take(40)
    }

    fun download(subtitle: OnlineSubtitle): Uri {
        if (subtitle.provider == PUBLIC_PROVIDER) return downloadPublic(subtitle)
        return download(subtitle.fileId)
    }

    private fun downloadPublic(subtitle: OnlineSubtitle): Uri {
        require(subtitle.sourceId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) {
            "معرّف الترجمة غير صالح"
        }
        val dir = File(app.cacheDir, "online-subtitles").apply { mkdirs() }
        val cachePrefix = "subtitle-public-${subtitle.sourceId.hashCode().toUInt()}"
        dir.listFiles()?.firstOrNull {
            it.name.startsWith("$cachePrefix.") && isValidCachedSubtitle(it)
        }?.let { return Uri.fromFile(it) }

        val endpoint = "$PUBLIC_API/search/download/${subtitle.sourceId}"
        val request = Request.Builder().url(endpoint)
            .header("User-Agent", AGENT)
            .header("Accept", "application/json")
            .get().build()
        val downloadPath = execute(request).optString("download_url")
            .takeIf { it.isNotBlank() } ?: error("لم توفر الخدمة رابط تنزيل للترجمة")
        val downloadUri = when {
            downloadPath.startsWith("https://") -> Uri.parse(downloadPath)
            downloadPath.startsWith("/strapi/") -> Uri.parse("https://$PUBLIC_HOST$downloadPath")
            else -> Uri.parse("$PUBLIC_BASE/${downloadPath.trimStart('/')}")
        }
        require(downloadUri.scheme == "https" && downloadUri.host == PUBLIC_HOST) {
            "رابط تنزيل الترجمة غير آمن"
        }
        val response = client.newCall(Request.Builder().url(downloadUri.toString()).get().build()).execute()
        response.use {
            if (!it.isSuccessful) error("تعذر تنزيل الترجمة (HTTP ${it.code})")
            val bytes = it.body?.byteStream()?.use(::readBounded) ?: error("ملف الترجمة فارغ")
            require(bytes.isNotEmpty()) { "ملف الترجمة فارغ" }
            val target = if (bytes.isZip()) {
                extract(bytes, dir, subtitle.sourceId.hashCode().toLong(), cachePrefix)
            } else {
                val ext = detect(bytes) ?: subtitle.format.takeIf { it in FORMATS }
                    ?: error("صيغة ملف الترجمة غير مدعومة")
                writeSubtitleAtomically(dir, "$cachePrefix.$ext", bytes)
            }
            require(target.length() in 1..MAX_BYTES.toLong()) { "حجم ملف الترجمة غير صالح" }
            return Uri.fromFile(target)
        }
    }

    fun search(title: String, language: String, season: Int?, episode: Int?): List<OnlineSubtitle> {
        val key = credentials().first
        require(title.isNotBlank()) { "أدخل اسم الفيلم أو المسلسل" }
        val url = Uri.parse("$BASE/subtitles").buildUpon()
            .appendQueryParameter("query", title.trim().take(180).lowercase(Locale.ROOT))
            .appendQueryParameter("languages", language)
            .apply {
                if (season != null && episode != null) {
                    appendQueryParameter("type", "episode")
                    appendQueryParameter("season_number", season.toString())
                    appendQueryParameter("episode_number", episode.toString())
                }
            }.build().toString()
        val req = Request.Builder().url(url).header("Api-Key", key).header("User-Agent", AGENT)
            .header("Accept", "application/json").get().build()
        val data = execute(req).optJSONArray("data") ?: return emptyList()
        return buildList {
            for (i in 0 until data.length()) {
                val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: continue
                val files = a.optJSONArray("files") ?: continue
                for (j in 0 until files.length()) {
                    val f = files.optJSONObject(j) ?: continue
                    val id = f.optLong("file_id")
                    val ext = f.optString("file_name").substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (id > 0 && ext in FORMATS) add(
                        OnlineSubtitle(id, a.optString("language"), a.optString("release", "Subtitle"),
                            a.optInt("download_count"), ext)
                    )
                }
            }
        }.distinctBy { it.fileId }.take(40)
    }

    fun download(fileId: Long): Uri {
        require(fileId > 0)
        val dir = File(app.cacheDir, "online-subtitles").apply { mkdirs() }
        dir.listFiles()?.firstOrNull {
            it.name.startsWith("subtitle-$fileId.") && isValidCachedSubtitle(it)
        }?.let { return Uri.fromFile(it) }
        val (key, account) = credentials()
        val loginBody = JSONObject().put("username", account.first).put("password", account.second)
            .toString().toRequestBody(JSON)
        val login = Request.Builder().url("$BASE/login").header("Api-Key", key)
            .header("User-Agent", AGENT).post(loginBody).build()
        val token = execute(login).optString("token").takeIf { it.isNotBlank() }
            ?: error("تعذر تسجيل الدخول إلى OpenSubtitles")
        val body = JSONObject().put("file_id", fileId).toString().toRequestBody(JSON)
        val req = Request.Builder().url("$BASE/download").header("Api-Key", key)
            .header("Authorization", "Bearer $token").header("User-Agent", AGENT)
            .post(body).build()
        val link = execute(req).optString("link")
        require(link.startsWith("https://")) { "رابط الترجمة غير صالح" }
        val response = client.newCall(Request.Builder().url(link).get().build()).execute()
        response.use {
            if (!it.isSuccessful) error("تعذر تنزيل الترجمة (HTTP " + it.code + ")")
            val bytes = it.body?.byteStream()?.use(::readBounded) ?: error("ملف الترجمة فارغ")
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "حجم ملف الترجمة غير صالح" }
            val target = if (bytes.isZip()) extract(bytes, dir, fileId) else {
                val ext = detect(bytes) ?: error("صيغة ملف الترجمة غير مدعومة")
                writeSubtitleAtomically(dir, "subtitle-$fileId.$ext", bytes)
            }
            require(target.length() in 1..MAX_BYTES.toLong())
            return Uri.fromFile(target)
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
                        val n = zip.read(buffer)
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

    private fun credentials(): Pair<String, Pair<String, String>> {
        val key = vault.load(KEY_SLOT)?.first ?: error("أدخل مفتاح API في نافذة البحث")
        val account = vault.load(USER_SLOT) ?: error("أدخل حساب OpenSubtitles")
        require(key.isNotBlank() && account.first.isNotBlank() && account.second.isNotBlank())
        return key to account
    }

    private fun execute(request: Request): JSONObject {
        val response = client.newCall(request).execute()
        response.use {
            val text = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                error(message?.takeIf(String::isNotBlank) ?: "خدمة الترجمة أعادت خطأ (HTTP " + it.code + ")")
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
            val count = input.read(buffer)
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
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (target.exists() && !target.delete()) error("تعذر استبدال ملف الترجمة القديم")
            if (!temporary.renameTo(target)) error("تعذر اعتماد ملف الترجمة بعد التنزيل")
            return target
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun ByteArray.isZip() = size > 3 && this[0] == 0x50.toByte() && this[1] == 0x4b.toByte()
    private fun detect(bytes: ByteArray): String? {
        val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r')
        return when {
            text.startsWith("WEBVTT", true) -> "vtt"
            text.contains("-->") -> "srt"
            text.startsWith("[Script Info]", true) || text.contains("[Events]", true) -> "ass"
            else -> null
        }
    }

    private fun isValidSubtitlePayload(bytes: ByteArray, extension: String): Boolean {
        if (extension !in FORMATS || bytes.isEmpty() || bytes.size > MAX_BYTES) return false
        val detected = detect(bytes) ?: return false
        return when (extension) {
            "ssa" -> detected == "ass"
            else -> detected == extension
        }
    }

    private companion object {
        const val BASE = "https://api.opensubtitles.com/api/v1"
        const val PUBLIC_HOST = "subtitles.website"
        const val PUBLIC_BASE = "https://subtitles.website/strapi"
        const val PUBLIC_API = "$PUBLIC_BASE/api"
        const val PUBLIC_PROVIDER = "subtitleSearchEngine"
        const val AGENT = "MalekPlay v2.0.0"
        const val KEY_SLOT = "opensubtitles_api_key"
        const val USER_SLOT = "opensubtitles_account"
        const val MAX_BYTES = 8 * 1024 * 1024
        val JSON = "application/json; charset=utf-8".toMediaType()
        val FORMATS = setOf("srt", "ass", "ssa", "vtt")
    }
}
