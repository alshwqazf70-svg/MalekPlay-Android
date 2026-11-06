package com.malekplay.network

import android.util.Xml
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class DlnaEntry(
    val id: String,
    val parentId: String,
    val title: String,
    val isContainer: Boolean,
    val mediaUrl: String? = null,
    val sizeBytes: Long = 0L,
    val mimeType: String? = null
)

internal fun isPrivateLanHost(host: String): Boolean = runCatching {
    // Discovery supplies numeric packet-source addresses; never resolve arbitrary DNS names here.
    require(host.contains(':') || host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")))
    val address = InetAddress.getByName(host)
    if (address.isSiteLocalAddress || address.isLinkLocalAddress) true
    else {
        val bytes = address.address
        (bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100 &&
            (bytes[1].toInt() and 0xC0) == 0x40) ||
            (bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC)
    }
}.getOrDefault(false)

internal fun validateDlnaDeviceUrl(raw: String, deviceHost: String): URI {
    require(isPrivateLanHost(deviceHost)) { "يُسمح بتصفح DLNA على عناوين الشبكة المحلية فقط" }
    val uri = URI(raw).normalize()
    require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
        "خادم DLNA أعلن رابطاً غير آمن"
    }
    require(uri.userInfo == null && uri.fragment == null && uri.host?.equals(deviceHost, true) == true) {
        "رابط DLNA لا يطابق الجهاز الذي تم اكتشافه"
    }
    require(uri.toString().length <= 4096) { "رابط DLNA طويل جداً" }
    return uri
}

data class DlnaDirectoryPage(
    val entries: List<DlnaEntry>,
    val totalMatches: Int,
    val updateId: String?
)

/** Bounded UPnP ContentDirectory browser restricted to the responding LAN device. */
class DlnaContentDirectoryClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
) {
    suspend fun browse(
        device: DiscoveredNetworkDevice,
        network: Network,
        objectId: String = "0",
        startingIndex: Int = 0,
        requestedCount: Int = 100
    ): DlnaDirectoryPage = withContext(Dispatchers.IO) {
        require(device.kind == NetworkDeviceKind.MEDIA_SERVER) { "الجهاز لا يعلن خدمة خادم وسائط DLNA" }
        require(startingIndex >= 0 && requestedCount in 1..200) { "حدود تصفح DLNA غير صالحة" }
        require(objectId.length <= 512 && objectId.none { it.isISOControl() }) { "معرّف مجلد DLNA غير صالح" }
        val descriptionUrl = device.descriptionUrl
            ?: throw IllegalStateException("لم يعلن الجهاز رابط وصف DLNA؛ لا يمكن فتح مكتبته")
        val description = validateDlnaDeviceUrl(descriptionUrl, device.host)
        val networkClient = http.newBuilder().socketFactory(network.socketFactory).build()
        val descriptionXml = execute(networkClient,
            Request.Builder().url(description.toString()).get().header("Accept", "application/xml,text/xml").build()
        )
        val endpoint = findContentDirectoryEndpoint(descriptionXml, description, device.host)
            ?: throw IllegalStateException("الجهاز لا يوفر خدمة ContentDirectory لتصفح الوسائط")
        val soap = buildBrowseEnvelope(objectId, startingIndex, requestedCount)
        val request = Request.Builder()
            .url(endpoint.controlUrl.toString())
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("SOAPAction", "\"${endpoint.serviceType}#Browse\"")
            .post(soap.toRequestBody("text/xml; charset=utf-8".toMediaType()))
            .build()
        parseBrowseResponse(execute(networkClient, request), device.host)
    }

    private fun execute(client: OkHttpClient, request: Request): String {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("خادم DLNA أعاد HTTP ${response.code}")
            val body = response.body ?: throw IllegalStateException("استجابة DLNA فارغة")
            if (body.contentLength() > MAX_XML_BYTES) throw IllegalStateException("استجابة DLNA أكبر من الحد المسموح")
            val source = body.source()
            source.request(MAX_XML_BYTES.toLong() + 1L)
            require(source.buffer.size <= MAX_XML_BYTES) { "استجابة DLNA أكبر من الحد المسموح" }
            return source.readUtf8()
        }
    }

    private fun findContentDirectoryEndpoint(descriptionXml: String, base: URI, deviceHost: String): ContentDirectoryEndpoint? {
        val parser = parser(descriptionXml)
        var inService = false
        var serviceType = ""
        var controlPath = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (localName(parser.name) == "service") {
                    inService = true
                    serviceType = ""
                    controlPath = ""
                }
                XmlPullParser.END_TAG -> if (localName(parser.name) == "service" && inService) {
                    if (serviceType.contains("ContentDirectory", true) && controlPath.isNotBlank()) {
                        val resolved = base.resolve(controlPath.trim())
                        return ContentDirectoryEndpoint(validateDlnaDeviceUrl(resolved.toString(), deviceHost), serviceType.trim())
                    }
                    inService = false
                }
            }
            if (event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) {
                // Text events are consumed by nextText() in the matching parent tag below.
            }
            if (event == XmlPullParser.START_TAG) {
                when (localName(parser.name)) {
                    "serviceType" -> serviceType = parser.nextText()
                    "controlURL" -> controlPath = parser.nextText()
                }
            }
            event = parser.next()
        }
        return null
    }

    private fun parseBrowseResponse(xml: String, deviceHost: String): DlnaDirectoryPage {
        val parser = parser(xml)
        var resultXml: String? = null
        var total = 0
        var updateId: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (localName(parser.name)) {
                    "Result" -> resultXml = parser.nextText()
                    "TotalMatches" -> total = parser.nextText().toIntOrNull() ?: 0
                    "UpdateID" -> updateId = parser.nextText().take(80)
                    "Fault" -> throw IllegalStateException("خادم DLNA رفض طلب التصفح")
                }
            }
            event = parser.eventType
            if (event != XmlPullParser.END_DOCUMENT) event = parser.next()
        }
        val didl = resultXml ?: throw IllegalStateException("لم يُرجع خادم DLNA محتوى المكتبة")
        return DlnaDirectoryPage(parseDidl(didl, deviceHost), total.coerceAtLeast(0), updateId)
    }

    private fun parseDidl(xml: String, deviceHost: String): List<DlnaEntry> {
        val parser = parser(xml)
        val entries = ArrayList<DlnaEntry>()
        var current: MutableDlnaEntry? = null
        var currentResource: String? = null
        var currentResourceSize = 0L
        var currentMime: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && entries.size < MAX_ENTRIES) {
            when (event) {
                XmlPullParser.START_TAG -> when (localName(parser.name)) {
                    "container", "item" -> {
                        current = MutableDlnaEntry(
                            id = parser.getAttributeValue(null, "id").orEmpty(),
                            parentId = parser.getAttributeValue(null, "parentID").orEmpty(),
                            title = "",
                            isContainer = localName(parser.name) == "container"
                        )
                        currentResource = null
                        currentResourceSize = 0L
                        currentMime = null
                    }
                    "title" -> if (current != null) current!!.title = parser.nextText().take(300)
                    "res" -> if (current != null) {
                        val protocolInfo = parser.getAttributeValue(null, "protocolInfo").orEmpty()
                        val size = parser.getAttributeValue(null, "size")?.toLongOrNull() ?: 0L
                        val candidate = parser.nextText().trim()
                        if (currentResource == null && candidate.length <= 4096) {
                            currentResource = candidate
                            currentResourceSize = size.coerceAtLeast(0L)
                            currentMime = protocolInfo.split(':').getOrNull(2)?.takeIf { it.length <= 128 }
                        }
                    }
                }
                XmlPullParser.END_TAG -> if (localName(parser.name) == "container" || localName(parser.name) == "item") {
                    val entry = current
                    if (entry != null && entry.id.isNotBlank() && entry.id.length <= 512) {
                        val safeUrl = if (entry.isContainer) null else currentResource?.let {
                            runCatching { validateDlnaDeviceUrl(it, deviceHost).toString() }.getOrNull()
                        }
                        if (entry.isContainer || safeUrl != null) {
                            entries += DlnaEntry(
                                id = entry.id,
                                parentId = entry.parentId,
                                title = entry.title.ifBlank { "وسيط بدون اسم" },
                                isContainer = entry.isContainer,
                                mediaUrl = safeUrl,
                                sizeBytes = currentResourceSize,
                                mimeType = currentMime
                            )
                        }
                    }
                    current = null
                }
            }
            event = parser.next()
        }
        return entries
    }

    private fun parser(xml: String): XmlPullParser {
        require(xml.length <= MAX_XML_BYTES) { "ملف XML أكبر من الحد المسموح" }
        return Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
            setInput(StringReader(xml))
            next()
        }
    }

    private fun buildBrowseEnvelope(objectId: String, start: Int, count: Int): String {
        fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        return """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
<s:Body><u:Browse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1">
<ObjectID>${escape(objectId)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>
<StartingIndex>$start</StartingIndex><RequestedCount>$count</RequestedCount><SortCriteria></SortCriteria>
</u:Browse></s:Body></s:Envelope>"""
    }

    private fun localName(name: String): String = name.substringAfter(':').lowercase()

    private data class ContentDirectoryEndpoint(val controlUrl: URI, val serviceType: String)

    private data class MutableDlnaEntry(
        val id: String,
        val parentId: String,
        var title: String,
        val isContainer: Boolean
    )

    private companion object {
        const val MAX_XML_BYTES = 1_500_000
        const val MAX_ENTRIES = 200
    }
}
