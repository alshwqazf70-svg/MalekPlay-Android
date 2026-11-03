package com.malekplay.network

import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** SMB2/3 share listing. Credentials are passed separately from the server/share path. */
class SmbClient {
    suspend fun list(
        host: String,
        shareName: String,
        path: String,
        username: String,
        password: String,
        domain: String = ""
    ): List<NetworkEntry> = withContext(Dispatchers.IO) {
        require(host.isNotBlank() && shareName.isNotBlank())
        val client = SMBClient()
        client.use { smb ->
            smb.connect(host).use { connection ->
                val auth = AuthenticationContext(username, password.toCharArray(), domain)
                val session = connection.authenticate(auth)
                session.use {
                    val share = session.connectShare(shareName) as? DiskShare
                        ?: error("المشاركة ليست مجلداً من نوع DiskShare")
                    share.use {
                        val normalized = path.trim('/').replace('/', '\\')
                        share.list(normalized).mapNotNull { entry ->
                            val name = entry.fileName
                            if (name == "." || name == "..") null
                            else {
                                val directory = (entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L
                                NetworkEntry(
                                    name = name,
                                    path = listOf(shareName.trim('/'), path.trim('/'), name).filter { it.isNotBlank() }.joinToString("/"),
                                    isDirectory = directory,
                                    sizeBytes = entry.endOfFile.coerceAtLeast(0L)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
