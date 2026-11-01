package com.malekplay.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

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
        require(base.userInfo == null) { "ضع بيانات الدخول في مخزن الاعتمادات، وليس داخل الرابط" }
        require(base.host.isNotBlank())
        val target = URL(base, path.trimStart('/'))
        require(target.protocol.equals("https", true) && target.host.equals(base.host, true)) {
            "مسار WebDAV خارج الخادم المحدد"
        }
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
            buildList {
                for (i in 0 until responses.length) {
                    val item = responses.item(i) as? Element ?: continue
                    val href = item.getElementsByTagNameNS("*", "href")
                        .item(0)?.textContent?.trim().orEmpty()
                    if (href.isBlank()) continue
                    val resolved = URL(base, href)
                    if (!resolved.host.equals(base.host, true) || !resolved.protocol.equals("https", true)) continue
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
