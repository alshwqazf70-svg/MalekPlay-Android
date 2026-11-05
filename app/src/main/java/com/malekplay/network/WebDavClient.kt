package com.malekplay.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import android.net.Uri

/** WebDAV directory listing. HTTPS is mandatory and platform TLS validation stays enabled. */
class WebDavClient {
    private val http = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    suspend fun list(
        endpoint: String,
        path: String,
        username: String,
        password: String
    ): List<NetworkEntry> = withContext(Dispatchers.IO) {
        val base = URL(endpoint)
        require(base.protocol.equals("https", true)) { "يجب استخدام WebDAV عبر HTTPS فقط" }
        require(base.userInfo == null && base.query == null && base.ref == null) {
            "يجب أن يكون عنوان WebDAV خالياً من بيانات الدخول والاستعلام"
        }
        require(base.host.isNotBlank() && (base.path.isNullOrBlank() || base.path == "/")) {
            "عنوان WebDAV الأساسي غير صالح"
        }

        // Build each decoded segment independently so encoded slashes, traversal
        // segments and control characters cannot alter the requested collection.
        val safeSegments = validateWebDavPath(path)
        val targetUri = Uri.Builder()
            .scheme("https")
            .encodedAuthority(base.authority)
            .apply { safeSegments.forEach { appendPath(it) } }
            .build()
        val target = URL(targetUri.toString())
        val token = okhttp3.Credentials.basic(username, password, StandardCharsets.UTF_8)
        val request = Request.Builder()
            .url(target)
            .header("Depth", "1")
            .header("Authorization", token)
            .method(
                "PROPFIND",
                PROPFIND_BODY.toRequestBody("application/xml; charset=utf-8".toMediaType())
            )
            .build()
        http.newCall(request).execute().use { response ->
            require(response.code == 207 || response.isSuccessful) {
                "فشل WebDAV برمز HTTP ${response.code}; لم يتم اتباع التحويلات لحماية بيانات الدخول"
            }
            val body = response.body ?: error("استجابة WebDAV فارغة")
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = body.byteStream().use { factory.newDocumentBuilder().parse(it) }
            val responses = document.getElementsByTagNameNS("*", "response")
            val collectionPath = target.toURI().normalize().path
            val targetPort = if (target.port == -1) 443 else target.port
            buildList {
                for (i in 0 until responses.length) {
                    val item = responses.item(i) as? Element ?: continue
                    val href = item.getElementsByTagNameNS("*", "href")
                        .item(0)?.textContent?.trim().orEmpty()
                    if (href.isBlank()) continue
                    val resolved = runCatching { URL(target, href) }.getOrNull() ?: continue
                    val resolvedPort = if (resolved.port == -1) 443 else resolved.port
                    val resolvedPath = runCatching { resolved.toURI().normalize().path }.getOrNull()
                        ?: continue
                    if (!resolved.protocol.equals("https", true) ||
                        !resolved.host.equals(base.host, true) ||
                        resolvedPort != targetPort ||
                        resolved.userInfo != null ||
                        resolved.query != null ||
                        resolved.ref != null ||
                        !isWithinWebDavCollection(collectionPath, resolvedPath)
                    ) continue

                    val name = item.getElementsByTagNameNS("*", "displayname")
                        .item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
                        ?: resolved.path.substringAfterLast('/').ifBlank { "/" }
                    val collection = item.getElementsByTagNameNS("*", "collection").length > 0
                    val size = item.getElementsByTagNameNS("*", "getcontentlength")
                        .item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
                    if (resolved.toExternalForm().trimEnd('/') != target.toExternalForm().trimEnd('/')) {
                        add(NetworkEntry(name, resolved.toExternalForm(), collection, size.coerceAtLeast(0L)))
                    }
                }
            }
        }
    }

    private companion object {
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:"><d:prop>
            <d:displayname/><d:getcontentlength/><d:resourcetype/><d:getlastmodified/>
            </d:prop></d:propfind>"""
    }
}

/**
 * Validates and decodes a WebDAV path before it is appended to the trusted HTTPS
 * authority. Returned segments are decoded; callers must encode them as path
 * segments rather than concatenate them into a URL.
 */
internal fun validateWebDavPath(path: String): List<String> {
    require(path.length <= 4096 && '?' !in path && '#' !in path && "://" !in path) {
        "مسار WebDAV غير صالح أو طويل جداً"
    }
    require(path.none { it.isISOControl() || it.code == 92 }) {
        "مسار WebDAV يحتوي على محارف غير مسموحة"
    }
    val rawSegments = path.split('/').filter { it.isNotEmpty() }
    require(rawSegments.size <= 128) { "مسار WebDAV يحتوي على مكونات كثيرة جداً" }
    return rawSegments.map { raw ->
        val decoded = try {
            URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("ترميز مسار WebDAV غير صالح", error)
        }
        require(
            decoded.isNotEmpty() &&
                decoded.length <= 255 &&
                decoded != "." &&
                decoded != ".." &&
                '/' !in decoded &&
                decoded.none { it.code == 92 } &&
                decoded.none { it.isISOControl() } &&
                !Regex("%[0-9a-fA-F]{2}").containsMatchIn(decoded)
        ) { "مسار WebDAV يحتوي على مكوّن غير آمن" }
        decoded
    }
}

private fun isWithinWebDavCollection(collectionPath: String, candidatePath: String): Boolean {
    val root = collectionPath.trimEnd('/').ifBlank { "/" }
    return if (root == "/") candidatePath.startsWith("/")
    else candidatePath == root || candidatePath.startsWith("$root/")
}
