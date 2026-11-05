package com.malekplay.network

data class NetworkEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long = 0L
)

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
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "معرّف مصدر الشبكة غير صالح"
        }
        require(isValidHost(host)) {
            "عنوان الخادم غير صالح: أدخل اسم نطاق أو عنوان IP فقط"
        }
        require(port in 1..65535) {
            "منفذ الاتصال يجب أن يكون بين 1 و65535"
        }
        require(rootPath.length <= 2048 && rootPath.none(::isUnsafeControl)) {
            "مسار المصدر طويل جداً أو يحتوي على محارف تحكم غير مسموحة"
        }
        require(username.length <= 512 && username.none(::isUnsafeControl)) {
            "اسم المستخدم طويل جداً أو يحتوي على محارف تحكم غير مسموحة"
        }
        require(displayName.length <= 128 && displayName.none(::isUnsafeControl)) {
            "اسم العرض طويل جداً أو يحتوي على محارف تحكم غير مسموحة"
        }
    }

    private companion object {
        /**
         * Host is deliberately stored without a URL scheme, path, credentials,
         * query or fragment. IPv4, IPv6 literals (including bracketed IPv6),
         * DNS names and IDN names remain supported.
         */
        fun isValidHost(value: String): Boolean {
            if (value.isBlank() || value.length > 253) return false
            if (value != value.trim()) return false
            if (value.any { it.isWhitespace() || isUnsafeControl(it) }) return false
            if (value.any { it in "/\\\\@?#" }) return false
            return true
        }

        fun isUnsafeControl(char: Char): Boolean =
            char == '\u0000' || char == '\r' || char == '\n' || char.isISOControl()
    }
}
