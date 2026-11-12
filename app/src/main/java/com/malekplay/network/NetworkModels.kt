package com.malekplay.network

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies an imported SFTP known_hosts file without ever writing more than 1 MB.
 * The caller owns and closes both streams.
 */
private const val MAX_ZERO_READS = 3

internal fun copyKnownHostsBounded(
    input: InputStream,
    output: OutputStream,
    maxBytes: Long = 1_000_000L
): Long {
    require(maxBytes > 0L) { "حد حجم ملف known_hosts غير صالح" }
    val buffer = ByteArray(8 * 1024)
    var total = 0L
    var consecutiveZeroReads = 0
    try {
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) {
                consecutiveZeroReads++
                if (consecutiveZeroReads >= MAX_ZERO_READS) {
                    throw IOException("تعذر استيراد ملف known_hosts: لم يُرجع مصدر الملف أي بيانات")
                }
                continue
            }
            consecutiveZeroReads = 0
            require(count.toLong() <= maxBytes - total) {
                "ملف known_hosts أكبر من الحد المسموح (1 ميجابايت)"
            }
            output.write(buffer, 0, count)
            total += count
        }
        return total
    } finally {
        buffer.fill(0)
    }
}

/** Rejects the reserved delimiter used by the encrypted credential record format. */
internal fun validateNetworkCredentials(username: String, password: String) {
    require('\u0000' !in username && '\u0000' !in password) {
        "Network credentials must not contain the reserved separator"
    }
}

/** Canonicalizes a server host before it is stored or passed to network clients. */
fun normalizeNetworkHost(rawHost: String): String {
    var host = rawHost.trim()
    if (host.startsWith("https://", ignoreCase = true)) {
        host = host.substring(8)
    } else if (host.startsWith("http://", ignoreCase = true)) {
        host = host.substring(7)
    }
    host = host.trimEnd('/')
    if (host.startsWith("[") && host.endsWith("]")) {
        host = host.substring(1, host.length - 1)
    }

    require(host.isNotBlank() && host.length <= 253) { "اسم خادم الشبكة غير صالح" }
    require(host.none { it.isWhitespace() || it.isISOControl() }) { "اسم الخادم لا يجب أن يحتوي على مسافات أو محارف تحكم" }
    require(host.none {
        it == '/' || it == '\\' || it == '@' || it == '?' || it == '#'
    }) { "أدخل اسم الخادم فقط دون مسار أو بيانات رابط" }
    require(host.count { it == ':' } != 1) { "لا تضع المنفذ داخل اسم الخادم؛ أدخله في حقل المنفذ" }
    return host
}

/** Formats an HTTPS endpoint correctly for DNS names, IPv4 and IPv6 literals. */
fun httpsNetworkEndpoint(host: String, port: Int): String {
    require(port in 1..65535) { "منفذ الشبكة غير صالح" }
    val normalized = normalizeNetworkHost(host)
    val authorityHost = if (normalized.contains(':')) "[$normalized]" else normalized
    return "https://$authorityHost:$port"
}

data class NetworkEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L
)

/** A remote directory entry must represent exactly one safe path component. */
internal fun isSafeNetworkEntryName(name: String): Boolean =
    name.isNotBlank() &&
        name != "." &&
        name != ".." &&
        name.length <= 255 &&
        '/' !in name &&
        name.none { it.code == 92 || it.isISOControl() }

/** SFTP paths are always absolute and may not escape through dot segments. */
internal fun normalizeSftpRemotePath(rawPath: String): String {
    require(rawPath.startsWith("/") && rawPath.length <= 4096) {
        "مسار SFTP يجب أن يكون مطلقاً وألا يتجاوز الحد المسموح"
    }
    val segments = rawPath.split('/').filter { it.isNotEmpty() }
    require(segments.size <= 128 && segments.all(::isSafeNetworkEntryName)) {
        "مسار SFTP يحتوي على مكونات غير آمنة أو يتجاوز الحدود المسموحة"
    }
    return "/" + segments.joinToString("/")
}

enum class NetworkProtocol { WEBDAV, SMB, SFTP }

data class NetworkProfile(
    val id: String,
    val protocol: NetworkProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val rootPath: String,
    val displayName: String = host
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(host == normalizeNetworkHost(host)) { "Host must be normalized before storing a profile" }
        require(port in 1..65535)
        require(rootPath.length <= 2048 && !rootPath.contains('\u0000'))
    }
}
