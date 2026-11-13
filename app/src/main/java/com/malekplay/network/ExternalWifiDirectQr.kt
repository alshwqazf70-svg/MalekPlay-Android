package com.malekplay.network

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Safely recognizes the observed Zapya/Kuaiya QR envelope.
 *
 * This is intentionally a parser, not a Zapya-compatible connector: the meaning of
 * its opaque "ps", "t" and "k" fields is undocumented. In particular, "ps" must
 * never be treated as a Wi-Fi Direct passphrase without an independently verified
 * protocol implementation.
 */
object ExternalWifiDirectQr {
    private const val EXPECTED_HOST = "kuaiya.cn"
    private const val EXPECTED_PATH = "/q"
    private const val MAX_PAYLOAD_LENGTH = 2048

    data class Details(
        val sessionId: String,
        val hostAddress: String,
        val frequencyMhz: Int?,
        val opaquePasswordToken: String?,
        val connectionType: String?,
        val opaqueVerificationToken: String?
    ) {
        /** The proprietary authentication fields are not sufficient to join a group. */
        val supportsAutomaticJoin: Boolean = false

        override fun toString(): String =
            "Details(sessionId=$sessionId, hostAddress=$hostAddress, " +
                "frequencyMhz=$frequencyMhz, opaquePasswordToken=[REDACTED], " +
                "connectionType=$connectionType, opaqueVerificationToken=[REDACTED], " +
                "supportsAutomaticJoin=$supportsAutomaticJoin)"
    }

    fun parse(payload: String): Details {
        require(payload.length in 1..MAX_PAYLOAD_LENGTH) { "حجم رمز QR الخارجي غير صالح." }
        val uri = runCatching { URI(payload) }
            .getOrElse { throw IllegalArgumentException("رابط QR الخارجي غير صالح.", it) }

        require(uri.scheme?.equals("http", ignoreCase = true) == true ||
            uri.scheme?.equals("https", ignoreCase = true) == true) {
            "صيغة رابط QR الخارجي غير مدعومة."
        }
        require(uri.host?.equals(EXPECTED_HOST, ignoreCase = true) == true &&
            uri.path == EXPECTED_PATH && uri.userInfo == null && uri.port == -1 &&
            uri.fragment == null) {
            "الرابط ليس رمز QR معروفاً من Kuaiya."
        }

        val parameters = parseQuery(uri.rawQuery.orEmpty())
        val sessionId = parameters["sid"]
            ?.takeIf { it.isNotBlank() && it.length <= 128 }
            ?: throw IllegalArgumentException("معرّف الجلسة غير موجود أو غير صالح.")

        val host = parameters["ip"]
            ?.takeIf(UltraShareTransport::isValidPrivateLanHost)
            ?: throw IllegalArgumentException("عنوان المضيف في QR ليس عنوان شبكة محلية صالحاً.")

        val frequency = parameters["freq"]?.let { value ->
            val parsed = value.toIntOrNull()
                ?: throw IllegalArgumentException("تردد Wi-Fi في QR غير صالح.")
            require(parsed in 2000..7125) { "تردد Wi-Fi في QR خارج النطاق المدعوم." }
            parsed
        }

        val passwordToken = parameters["ps"]?.also {
            require(it.length in 1..512 && it.all { char ->
                char.isLetterOrDigit() || char in "+/=_-"
            }) { "حقل مصادقة QR الخارجي غير صالح." }
        }
        val type = parameters["t"]?.also {
            require(it.length <= 8 && it.all(Char::isDigit)) {
                "حقل نوع الاتصال في QR غير صالح."
            }
        }
        val verificationToken = parameters["k"]?.also {
            require(it.length in 1..64 && it.all { char -> char in "0123456789abcdefABCDEF" }) {
                "حقل التحقق في QR غير صالح."
            }
        }

        return Details(
            sessionId = sessionId,
            hostAddress = host,
            frequencyMhz = frequency,
            opaquePasswordToken = passwordToken,
            connectionType = type,
            opaqueVerificationToken = verificationToken
        )
    }

    private fun parseQuery(rawQuery: String): Map<String, String> {
        require(rawQuery.isNotBlank()) { "حقول الاتصال غير موجودة في QR." }
        val result = LinkedHashMap<String, String>()
        rawQuery.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val separator = pair.indexOf('=')
            require(separator > 0) { "أحد حقول QR غير مكتمل." }
            val key = decode(pair.substring(0, separator))
            val value = decode(pair.substring(separator + 1))
            require(key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,31}"))) {
                "اسم أحد حقول QR غير صالح."
            }
            require(result.put(key, value) == null) {
                "يحتوي QR على حقل اتصال مكرر."
            }
        }
        return result
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (failure: IllegalArgumentException) {
        throw IllegalArgumentException("ترميز أحد حقول QR غير صالح.", failure)
    }
}
