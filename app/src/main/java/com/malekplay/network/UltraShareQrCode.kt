package com.malekplay.network

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

/** Versioned QR payloads carry a one-session secret, optional LAN fallbacks and an ephemeral peer invitation ID. */
object UltraShareQrCode {
    private const val SCHEMA = "malekplay"
    private const val AUTHORITY = "ultra-share"
    private const val VERSION = "1"
    private const val MULTI_HOST_VERSION = "2"
    private const val INVITATION_VERSION = "3"
    private const val HOTSPOT_VERSION = "4"
    private const val UNIFIED_VERSION = "5"
    // v2 was already used by the legacy multi-host format; keep it immutable.
    // The richer room invitation therefore uses a new additive schema version.
    private const val ROOM_VERSION = "6"
    private const val MAX_QR_HOSTS = 8
    private const val MAX_DECODE_DIMENSION = 2048

    /** Builds a receiver QR using the authenticated control endpoint, never the data port. */
    fun createReceiverPayload(host: String, pairingSecret: String): String =
        createPayload(host, UltraShareControlChannel.DEFAULT_PORT, pairingSecret)
    /** Backward-compatible version 2 QR for peers that still rely on shared-LAN host addresses. */
    fun createReceiverPayload(hosts: List<String>, pairingSecret: String): String {
        val safeHosts = hosts.asSequence()
            .map { it.trim() }
            .filter { UltraShareCore.isValidPrivateLanHost(it) }
            .distinct()
            .take(MAX_QR_HOSTS)
            .toList()
        require(safeHosts.isNotEmpty()) { "لا يوجد عنوان شبكة محلي صالح لرمز QR." }
        require(UltraShareCore.isValidPairingSecret(pairingSecret)) {
            "رمز الاقتران غير صالح."
        }
        return Uri.Builder()
            .scheme(SCHEMA)
            .authority(AUTHORITY)
            .appendQueryParameter("v", MULTI_HOST_VERSION)
            .apply { safeHosts.forEach { appendQueryParameter("host", it) } }
            .appendQueryParameter("port", UltraShareControlChannel.DEFAULT_PORT.toString())
            .appendQueryParameter("secret", pairingSecret)
            .build()
            .toString()
    }

    /** Version 3 QR carries a public, stable peer identity for Wi-Fi Direct service discovery. */
    fun createReceiverPayload(
        hosts: List<String>,
        pairingSecret: String,
        invitationId: String
    ): String {
        require(invitationId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "هوية دعوة الجهاز غير صالحة."
        }
        val safeHosts = hosts.asSequence()
            .map { it.trim() }
            .filter { UltraShareCore.isValidPrivateLanHost(it) }
            .distinct()
            .take(MAX_QR_HOSTS)
            .toList()
        require(UltraShareCore.isValidPairingSecret(pairingSecret)) {
            "رمز الاقتران غير صالح."
        }
        return Uri.Builder()
            .scheme(SCHEMA)
            .authority(AUTHORITY)
            .appendQueryParameter("v", INVITATION_VERSION)
            .apply { safeHosts.forEach { appendQueryParameter("host", it) } }
            .appendQueryParameter("peer", invitationId)
            .appendQueryParameter("port", UltraShareControlChannel.DEFAULT_PORT.toString())
            .appendQueryParameter("secret", pairingSecret)
            .build()
            .toString()
    }

    /** Version 4 carries temporary LocalOnlyHotspot credentials for system-mediated joining. */
    fun createHotspotReceiverPayload(
        hosts: List<String>,
        pairingSecret: String,
        ssid: String,
        password: String
    ): String {
        val safeHosts = hosts.asSequence()
            .map { it.trim() }
            .filter { UltraShareCore.isValidPrivateLanHost(it) }
            .distinct()
            .take(MAX_QR_HOSTS)
            .toList()
        require(safeHosts.isNotEmpty()) { "لا يوجد عنوان صالح لنقطة اتصال MalekPlayer." }
        require(UltraShareCore.isValidPairingSecret(pairingSecret)) { "رمز الاقتران غير صالح." }
        require(ssid.isNotBlank() && ssid.length <= 32) { "اسم شبكة MalekPlayer غير صالح." }
        require(password.length in 8..63) { "كلمة مرور شبكة MalekPlayer غير صالحة." }
        return Uri.Builder()
            .scheme(SCHEMA)
            .authority(AUTHORITY)
            .appendQueryParameter("v", HOTSPOT_VERSION)
            .apply { safeHosts.forEach { appendQueryParameter("host", it) } }
            .appendQueryParameter("port", UltraShareControlChannel.DEFAULT_PORT.toString())
            .appendQueryParameter("secret", pairingSecret)
            .appendQueryParameter("ssid", ssid)
            .appendQueryParameter("wifi_password", password)
            .build().toString()
    }

    /**
     * Version 5 combines every usable route in one invitation: current-LAN hosts,
     * Wi-Fi Direct SoftAP/LocalOnlyHotspot credentials, and optional DNS-SD peer ID.
     */
    fun createUnifiedReceiverPayload(
        hosts: List<String>,
        pairingSecret: String,
        ssid: String? = null,
        wifiPassword: String? = null,
        invitationId: String? = null,
        wifiSecurity: String? = "wpa2"
    ): String {
        val safeHosts = hosts.asSequence()
            .map { it.trim() }
            .filter { UltraShareCore.isValidPrivateLanHost(it) }
            .distinct()
            .take(MAX_QR_HOSTS)
            .toList()
        require(safeHosts.isNotEmpty()) { "لا يوجد عنوان شبكة محلي صالح لرمز QR." }
        require(UltraShareCore.isValidPairingSecret(pairingSecret)) { "رمز الاقتران غير صالح." }
        require((ssid == null) == (wifiPassword == null)) {
            "يجب توفير اسم شبكة Wi-Fi وكلمة مرورها معاً."
        }
        if (ssid != null && wifiPassword != null) {
            require(ssid.isNotBlank() && ssid.length <= 32) { "اسم شبكة Wi-Fi غير صالح." }
            require(wifiPassword.length in 8..63) { "كلمة مرور Wi-Fi غير صالحة." }
            require(wifiSecurity == "wpa2" || wifiSecurity == "wpa3") { "نوع حماية Wi-Fi غير مدعوم." }
        }
        invitationId?.let {
            require(it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "هوية دعوة الجهاز غير صالحة." }
        }
        return Uri.Builder()
            .scheme(SCHEMA)
            .authority(AUTHORITY)
            .appendQueryParameter("v", UNIFIED_VERSION)
            .apply {
                safeHosts.forEach { appendQueryParameter("host", it) }
                invitationId?.let { appendQueryParameter("peer", it) }
                ssid?.let { appendQueryParameter("ssid", it) }
                wifiPassword?.let { appendQueryParameter("wifi_password", it) }
                if (ssid != null) appendQueryParameter("wifi_security", wifiSecurity ?: "wpa2")
            }
            .appendQueryParameter("port", UltraShareControlChannel.DEFAULT_PORT.toString())
            .appendQueryParameter("secret", pairingSecret)
            .build()
            .toString()
    }

    /**
     * Current room invitation schema. This is additive to v1-v5: v2 is already
     * deployed as the multi-host legacy format and must never be reinterpreted.
     */
    fun createRoomPayload(
        host: String,
        port: Int = UltraShareControlChannel.DEFAULT_PORT,
        pairingSecret: String,
        deviceName: String,
        mode: String = "lan",
        ssid: String? = null,
        psk: String? = null
    ): String {
        val safeHost = host.trim()
        require(UltraShareCore.isValidPrivateLanHost(safeHost)) {
            "عنوان QR يجب أن يكون IPv4 خاصاً بالشبكة المحلية."
        }
        require(port in 1024..65535) { "منفذ الاقتران يجب أن يكون بين 1024 و65535." }
        require(UltraShareCore.isValidPairingSecret(pairingSecret)) { "رمز الاقتران غير صالح." }
        val safeName = deviceName.trim().take(64).ifBlank { "MalekPlayer Android" }
        require(mode in setOf("lan", "hotspot", "p2p")) { "نوع اتصال الغرفة غير صالح." }
        require((ssid == null) == (psk == null)) { "يجب توفير اسم الشبكة وكلمة مرورها معاً." }
        if (ssid != null && psk != null) {
            require(ssid.isNotBlank() && ssid.length <= 32) { "اسم شبكة Wi-Fi غير صالح." }
            require(psk.length in 8..63) { "كلمة مرور Wi-Fi غير صالحة." }
        }
        return Uri.Builder()
            .scheme(SCHEMA)
            .authority(AUTHORITY)
            .appendQueryParameter("v", ROOM_VERSION)
            .appendQueryParameter("host", safeHost)
            .appendQueryParameter("port", port.toString())
            .appendQueryParameter("secret", pairingSecret)
            .appendQueryParameter("name", safeName)
            .appendQueryParameter("mode", mode)
            .apply {
                ssid?.let { appendQueryParameter("ssid", it) }
                psk?.let { appendQueryParameter("psk", it) }
            }
            .build()
            .toString()
    }

    fun createPayload(host: String, port: Int, pairingSecret: String): String {
        require(port == UltraShareControlChannel.DEFAULT_PORT) { "منفذ قناة الاقتران غير متوافق." }
        require(UltraShareCore.isValidPrivateLanHost(host)) {
            "عنوان QR يجب أن يكون IPv4 خاصاً بالشبكة المحلية."
        }
        require(UltraShareCore.isValidPairingSecret(pairingSecret)) {
            "رمز الاقتران غير صالح."
        }
        return Uri.Builder()
            .scheme(SCHEMA)
            .authority(AUTHORITY)
            .appendQueryParameter("v", VERSION)
            .appendQueryParameter("host", host)
            .appendQueryParameter("port", port.toString())
            .appendQueryParameter("secret", pairingSecret)
            .build()
            .toString()
    }

    data class PairingDetails(
        val host: String,
        val port: Int,
        val pairingSecret: String,
        val hosts: List<String> = listOf(host),
        val invitationId: String? = null,
        val hotspotSsid: String? = null,
        val hotspotPassword: String? = null,
        val hotspotSecurity: String = "wpa2",
        val deviceName: String = "",
        val mode: String? = null
    )

    fun parsePayload(payload: String): PairingDetails {
        val uri = Uri.parse(payload)
        require(uri.scheme == SCHEMA && uri.authority == AUTHORITY) {
            "رمز QR ليس رمز اقتران MalekPlayer Ultra Share."
        }
        val version = uri.getQueryParameter("v")
        require(version == VERSION || version == MULTI_HOST_VERSION || version == INVITATION_VERSION || version == HOTSPOT_VERSION || version == UNIFIED_VERSION || version == ROOM_VERSION || version == ROOM_VERSION) {
            "إصدار رمز الاقتران غير مدعوم."
        }
        val hosts = (if (version == MULTI_HOST_VERSION || version == INVITATION_VERSION || version == HOTSPOT_VERSION || version == UNIFIED_VERSION) {
            uri.getQueryParameters("host")
        } else {
            listOfNotNull(uri.getQueryParameter("host"))
        }).asSequence()
            .map { it.trim() }
            .filter { UltraShareCore.isValidPrivateLanHost(it) }
            .distinct()
            .take(MAX_QR_HOSTS)
            .toList()
        val host = hosts.firstOrNull() ?: if (version == INVITATION_VERSION || version == UNIFIED_VERSION) "" else
            throw IllegalArgumentException("لا يحتوي رمز QR على عنوان IPv4 محلي صالح.")
        val port = requireNotNull(uri.getQueryParameter("port")) { "منفذ الجهاز غير موجود في رمز QR." }
            .toIntOrNull() ?: throw IllegalArgumentException("منفذ الاقتران غير صالح.")
        val secret = requireNotNull(uri.getQueryParameter("secret")) { "رمز الاقتران غير موجود في رمز QR." }
        require(UltraShareCore.isValidPairingSecret(secret)) { "رمز الاقتران في QR غير صالح." }
        if (version == ROOM_VERSION) {
            require(port in 1024..65535) { "منفذ الاقتران يجب أن يكون بين 1024 و65535." }
        } else {
            require(port == UltraShareControlChannel.DEFAULT_PORT) { "منفذ MalekPlayer Ultra Share غير متوافق." }
        }
        if (version == UNIFIED_VERSION) {
            require((uri.getQueryParameter("ssid") == null) == (uri.getQueryParameter("wifi_password") == null)) {
                "يجب توفير اسم شبكة Wi-Fi وكلمة مرورها معاً."
            }
        }
        val hotspotSsid = if (version == HOTSPOT_VERSION || version == UNIFIED_VERSION && uri.getQueryParameter("ssid") != null) {
            requireNotNull(uri.getQueryParameter("ssid")) { "اسم شبكة Wi-Fi غير موجود في QR." }
                .also { require(it.isNotBlank() && it.length <= 32) { "اسم شبكة Wi-Fi غير صالح." } }
        } else null
        val hotspotPassword = if (version == HOTSPOT_VERSION || version == UNIFIED_VERSION && uri.getQueryParameter("wifi_password") != null) {
            requireNotNull(uri.getQueryParameter("wifi_password")) { "كلمة مرور Wi-Fi غير موجودة في QR." }
                .also { require(it.length in 8..63) { "كلمة مرور Wi-Fi غير صالحة." } }
        } else null
        val invitationId = if (version == INVITATION_VERSION || version == UNIFIED_VERSION && uri.getQueryParameter("peer") != null) {
            requireNotNull(uri.getQueryParameter("peer")) { "هوية الجهاز غير موجودة في رمز QR." }
                .also {
                    require(it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                        "هوية الجهاز في رمز QR غير صالحة."
                    }
                }
        } else null
        val hotspotSecurity = if (hotspotSsid != null) {
            (uri.getQueryParameter("wifi_security") ?: "wpa2").lowercase()
                .also { require(it == "wpa2" || it == "wpa3") { "نوع حماية Wi-Fi غير مدعوم." } }
        } else "wpa2"
        require(hosts.isNotEmpty() || invitationId != null) {
            "لا يحتوي رمز QR على أي مسار اتصال صالح."
        }
        val deviceName = if (version == ROOM_VERSION) {
            requireNotNull(uri.getQueryParameter("name")) { "اسم الجهاز غير موجود في رمز QR." }
                .trim().take(64).also { require(it.isNotBlank()) { "اسم الجهاز غير صالح." } }
        } else ""
        val mode = if (version == ROOM_VERSION) {
            requireNotNull(uri.getQueryParameter("mode")) { "نوع اتصال الغرفة غير موجود في رمز QR." }
                .lowercase().also { require(it in setOf("lan", "hotspot", "p2p")) { "نوع اتصال الغرفة غير مدعوم." } }
        } else null
        if (version == ROOM_VERSION) {
            require((uri.getQueryParameter("ssid") == null) == (uri.getQueryParameter("psk") == null)) {
                "يجب توفير اسم شبكة Wi-Fi وكلمة مرورها معاً."
            }
        }
        val roomSsid = if (version == ROOM_VERSION) uri.getQueryParameter("ssid")?.also {
            require(it.isNotBlank() && it.length <= 32) { "اسم شبكة Wi-Fi غير صالح." }
        } else hotspotSsid
        val roomPsk = if (version == ROOM_VERSION) uri.getQueryParameter("psk")?.also {
            require(it.length in 8..63) { "كلمة مرور Wi-Fi غير صالحة." }
        } else hotspotPassword
        require(hosts.isNotEmpty()) { "لا يحتوي رمز QR على عنوان IPv4 محلي صالح." }
        return PairingDetails(host, port, secret, hosts, invitationId, roomSsid, roomPsk, hotspotSecurity, deviceName, mode)
    }

    fun createBitmap(payload: String, dimension: Int = 640): Bitmap {
        require(dimension in 256..1024) { "أبعاد رمز QR خارج النطاق المسموح." }
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.Q)
            put(EncodeHintType.MARGIN, 4)
        }
        val matrix = QRCodeWriter().encode(
            payload,
            BarcodeFormat.QR_CODE,
            dimension,
            dimension,
            hints
        )
        val pixels = IntArray(dimension * dimension)
        for (y in 0 until dimension) {
            for (x in 0 until dimension) {
                pixels[y * dimension + x] =
                    if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, dimension, dimension, Bitmap.Config.ARGB_8888)
    }

    fun decodeImage(contentResolver: ContentResolver, imageUri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val boundsInput = contentResolver.openInputStream(imageUri)
            ?: error("تعذر فتح صورة رمز QR.")
        boundsInput.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "الصورة المحددة غير صالحة." }

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_DECODE_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_DECODE_DIMENSION
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = contentResolver.openInputStream(imageUri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: error("تعذر قراءة صورة رمز QR.")
        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.TRY_HARDER, true)
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
            }
            return MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } finally {
            bitmap.recycle()
        }
    }
}
