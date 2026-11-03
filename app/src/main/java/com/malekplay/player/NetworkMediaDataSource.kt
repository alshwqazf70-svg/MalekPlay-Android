package com.malekplay.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File as SmbFile
import com.malekplay.network.NetworkCredentialVault
import com.malekplay.network.NetworkProfile
import com.malekplay.network.NetworkProfileStore
import com.malekplay.network.NetworkProtocol
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.RemoteFile
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.EnumSet

@UnstableApi
/**
 * Random-access Media3 source for saved SMB and SFTP profiles.
 * Credentials and host-key material are resolved from private app storage; they
 * are never placed in the playback Uri or MediaItem metadata.
 */
class NetworkMediaDataSource(
    private val context: Context,
    private val profileStore: NetworkProfileStore,
    private val credentialVault: NetworkCredentialVault
) : BaseDataSource(false) {

    private var currentUri: Uri? = null
    private var opened = false
    private var fileLength = C.LENGTH_UNSET.toLong()
    private var position = 0L

    private var smbClient: SMBClient? = null
    private var smbConnection: Connection? = null
    private var smbSession: Session? = null
    private var smbShare: DiskShare? = null
    private var smbFile: SmbFile? = null

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null
    private var sftpFile: RemoteFile? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        try {
            val profileId = dataSpec.uri.host
                ?: throw IOException("مصدر الشبكة غير محدد")
            val profile = profileStore.load().firstOrNull { it.id == profileId }
                ?: throw IOException("مصدر الشبكة لم يعد موجوداً")
            val expectedProtocol = when (dataSpec.uri.scheme?.lowercase()) {
                "maleksmb" -> NetworkProtocol.SMB
                "maleksftp" -> NetworkProtocol.SFTP
                else -> throw IOException("بروتوكول تشغيل الشبكة غير مدعوم")
            }
            require(profile.protocol == expectedProtocol) { "نوع المصدر لا يطابق رابط التشغيل" }
            val credentials = credentialVault.load(profile.id)
                ?: (profile.username to "")
            val segments = dataSpec.uri.pathSegments
            require(segments.isNotEmpty() && segments.none { it == "." || it == ".." || it.contains('\u0000') }) {
                "مسار الملف غير صالح"
            }
            require(dataSpec.position >= 0L) { "موضع القراءة غير صالح" }

            when (profile.protocol) {
                NetworkProtocol.SMB -> openSmb(profile, credentials.first, credentials.second, segments)
                NetworkProtocol.SFTP -> openSftp(profile, credentials.first, credentials.second, segments)
                NetworkProtocol.WEBDAV -> throw IOException("مصدر WebDAV يستخدم مسار HTTP")
            }

            if (dataSpec.position > fileLength) throw EOFException("موضع القراءة يتجاوز حجم الملف")
            position = dataSpec.position
            opened = true
            transferStarted(dataSpec)
            return (fileLength - position).coerceAtLeast(0L)
        } catch (error: Throwable) {
            close()
            if (error is IOException) throw error
            throw IOException("تعذر فتح الملف عبر الشبكة: ${error.localizedMessage ?: error.javaClass.simpleName}", error)
        }
    }

    private fun openSmb(
        profile: NetworkProfile,
        username: String,
        password: String,
        segments: List<String>
    ) {
        require(segments.size >= 2) { "مسار SMB يجب أن يتضمن المشاركة واسم الملف" }
        val client = SMBClient()
        smbClient = client
        val connection = client.connect(profile.host, profile.port)
        smbConnection = connection
        val session = connection.authenticate(
            AuthenticationContext(username, password.toCharArray(), "")
        )
        smbSession = session
        val share = session.connectShare(segments.first()) as? DiskShare
            ?: throw IOException("مشاركة SMB ليست قرصاً")
        smbShare = share
        val remotePath = segments.drop(1).joinToString("\\")
        val file = share.open(
            remotePath,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.noneOf(SMB2CreateOptions::class.java)
        ) as? SmbFile ?: throw IOException("المسار المحدد ليس ملفاً عادياً")
        smbFile = file
        fileLength = file.getFileInformation(FileStandardInformation::class.java).endOfFile
    }

    private fun openSftp(
        profile: NetworkProfile,
        username: String,
        password: String,
        segments: List<String>
    ) {
        val knownHosts = File(context.filesDir, "network/known_hosts/${profile.id}")
        require(knownHosts.isFile && knownHosts.length() > 0L) {
            "ملف known_hosts الموثوق غير موجود لمصدر SFTP"
        }
        val ssh = SSHClient()
        sshClient = ssh
        ssh.loadKnownHosts(knownHosts)
        ssh.connect(profile.host, profile.port)
        ssh.authPassword(username, password)
        val sftp = ssh.newSFTPClient()
        sftpClient = sftp
        val remotePath = "/" + segments.joinToString("/")
        val file = sftp.open(remotePath)
        sftpFile = file
        fileLength = file.length()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!opened) throw IOException("مصدر الشبكة غير مفتوح")
        val remaining = fileLength - position
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val requested = minOf(length.toLong(), remaining, MAX_READ_BYTES.toLong()).toInt()
        val count = when {
            smbFile != null -> smbFile!!.read(buffer, position, offset, requested)
            sftpFile != null -> sftpFile!!.read(position, buffer, offset, requested)
            else -> throw IOException("اتصال الشبكة غير جاهز")
        }
        if (count < 0) return C.RESULT_END_OF_INPUT
        if (count == 0) return 0
        position += count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        runCatching { sftpFile?.close() }
        sftpFile = null
        runCatching { sftpClient?.close() }
        sftpClient = null
        runCatching { sshClient?.disconnect() }
        runCatching { sshClient?.close() }
        sshClient = null

        runCatching { smbFile?.close() }
        smbFile = null
        runCatching { smbShare?.close() }
        smbShare = null
        runCatching { smbSession?.close() }
        smbSession = null
        runCatching { smbConnection?.close() }
        smbConnection = null
        runCatching { smbClient?.close() }
        smbClient = null

        currentUri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private companion object {
        const val MAX_READ_BYTES = 256 * 1024
    }
}
