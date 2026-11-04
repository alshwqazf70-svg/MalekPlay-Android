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
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class OnlineSubtitle(val fileId: Long, val language: String, val release: String, val downloads: Int, val format: String)

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
            val bytes = it.body?.bytes() ?: error("ملف الترجمة فارغ")
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "حجم ملف الترجمة غير صالح" }
            val dir = File(app.cacheDir, "online-subtitles").apply { mkdirs() }
            val target = if (bytes.isZip()) extract(bytes, dir, fileId) else {
                val ext = detect(bytes) ?: error("صيغة ملف الترجمة غير مدعومة")
                File(dir, "subtitle-$fileId.$ext").also { FileOutputStream(it).use { out -> out.write(bytes) } }
            }
            require(target.length() in 1..MAX_BYTES.toLong())
            return Uri.fromFile(target)
        }
    }

    private fun extract(bytes: ByteArray, dir: File, id: Long): File {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val ext = entry.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (!entry.isDirectory && ext in FORMATS) {
                    val file = File(dir, "subtitle-$id.$ext")
                    FileOutputStream(file).use { out ->
                        val buffer = ByteArray(8192); var total = 0
                        while (true) {
                            val n = zip.read(buffer); if (n < 0) break
                            total += n; require(total <= MAX_BYTES) { "ملف الترجمة كبير جدًا" }
                            out.write(buffer, 0, n)
                        }
                    }
                    return file
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

    private companion object {
        const val BASE = "https://api.opensubtitles.com/api/v1"
        const val AGENT = "MalekPlay v2.0.0"
        const val KEY_SLOT = "opensubtitles_api_key"
        const val USER_SLOT = "opensubtitles_account"
        const val MAX_BYTES = 8 * 1024 * 1024
        val JSON = "application/json; charset=utf-8".toMediaType()
        val FORMATS = setOf("srt", "ass", "ssa", "vtt")
    }
}
