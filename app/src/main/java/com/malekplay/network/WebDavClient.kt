package com.malekplay.network

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import javax.xml.parsers.DocumentBuilderFactory

/** WebDAV directory listing. HTTPS is mandatory and platform TLS validation stays enabled. */
class WebDavClient {
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
        require(target.protocol == "https" && target.host.equals(base.host, true)) {
            "مسار WebDAV خارج الخادم المحدد"
        }
        val connection = (target.openConnection() as HttpURLConnection).apply {
            requestMethod = "PROPFIND"
            connectTimeout = 12_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Depth", "1")
            setRequestProperty("Content-Type", "application/xml; charset=utf-8")
            val token = Base64.encodeToString(
                (username + ":" + password).toByteArray(StandardCharsets.UTF_8),
                Base64.NO_WRAP
            )
            setRequestProperty("Authorization", "Basic $token")
        }
        try {
            connection.outputStream.use {
                it.write(PROPFIND_BODY.toByteArray(StandardCharsets.UTF_8))
            }
            val status = connection.responseCode
            require(status == 207 || status in 200..299) { "فشل WebDAV برمز HTTP $status" }
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val document = connection.inputStream.use { factory.newDocumentBuilder().parse(it) }
            val responses = document.getElementsByTagNameNS("*", "response")
            buildList {
                for (i in 0 until responses.length) {
                    val response = responses.item(i) as? Element ?: continue
                    val href = response.getElementsByTagNameNS("*", "href")
                        .item(0)?.textContent?.trim().orEmpty()
                    if (href.isBlank()) continue
                    val resolved = URL(base, href)
                    if (!resolved.host.equals(base.host, true) || resolved.protocol != "https") continue
                    val name = response.getElementsByTagNameNS("*", "displayname")
                        .item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
                        ?: resolved.path.substringAfterLast('/').ifBlank { "/" }
                    val collection = response.getElementsByTagNameNS("*", "collection").length > 0
                    val size = response.getElementsByTagNameNS("*", "getcontentlength")
                        .item(0)?.textContent?.trim()?.toLongOrNull() ?: 0L
                    if (resolved.toExternalForm().trimEnd('/') != target.toExternalForm().trimEnd('/')) {
                        add(NetworkEntry(name, resolved.toExternalForm(), collection, size.coerceAtLeast(0L)))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:"><d:prop>
            <d:displayname/><d:getcontentlength/><d:resourcetype/><d:getlastmodified/>
            </d:prop></d:propfind>"""
    }
}
