package com.malekplay.network

import net.schmizz.sshj.SSHClient
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SFTP listing that refuses connections without an explicitly supplied known_hosts file.
 * No permissive host-key verifier is installed.
 */
class SftpClient @Inject constructor() {
    suspend fun list(
        host: String,
        port: Int,
        username: String,
        password: String,
        remotePath: String,
        knownHostsFile: File
    ): List<NetworkEntry> = withContext(Dispatchers.IO) {
        require(host.isNotBlank()) { "اسم خادم SFTP مطلوب" }
        require(port in 1..65535) { "منفذ SFTP غير صالح" }
        require(knownHostsFile.isFile && knownHostsFile.length() > 0L) {
            "يجب توفير ملف known_hosts موثوق قبل الاتصال"
        }
        val ssh = SSHClient()
        NetworkClientTimeouts.configureSsh(ssh)
        try {
            ssh.loadKnownHosts(knownHostsFile)
            ssh.connect(host, port)
            val passwordChars = password.toCharArray()
            try {
                ssh.authPassword(username, passwordChars)
            } finally {
                passwordChars.fill('\u0000')
            }
            ssh.newSFTPClient().use { sftp ->
                sftp.ls(remotePath).mapNotNull { item ->
                    val name = item.name
                    if (name == "." || name == "..") null
                    else NetworkEntry(
                        name = name,
                        path = remotePath.trimEnd('/') + "/" + name,
                        isDirectory = item.isDirectory,
                        sizeBytes = item.attributes.size
                    )
                }
            }
        } finally {
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
        }
    }
}
