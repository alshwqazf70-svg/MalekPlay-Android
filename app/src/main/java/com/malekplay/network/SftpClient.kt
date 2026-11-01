package com.malekplay.network

import net.schmizz.sshj.SSHClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SFTP listing that refuses connections without an explicitly supplied known_hosts file.
 * No permissive host-key verifier is installed.
 */
class SftpClient {
    suspend fun list(
        host: String,
        port: Int,
        username: String,
        password: String,
        remotePath: String,
        knownHostsFile: File
    ): List<NetworkEntry> = withContext(Dispatchers.IO) {
        require(knownHostsFile.isFile && knownHostsFile.length() > 0L) {
            "يجب توفير ملف known_hosts موثوق قبل الاتصال"
        }
        val ssh = SSHClient()
        try {
            ssh.loadKnownHosts(knownHostsFile)
            ssh.connect(host, port)
            ssh.authPassword(username, password)
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
            ssh.close()
        }
    }
}
