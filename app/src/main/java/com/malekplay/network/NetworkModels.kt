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
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(host.isNotBlank() && host.length <= 253)
        require(port in 1..65535)
        require(rootPath.length <= 2048 && !rootPath.contains('\u0000'))
    }
}
