package com.malekplay.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
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
import com.malekplay.network.NetworkClientTimeouts
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
    private var bytesRemaining = C.LENGTH_UNSET.toLong()
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
        if (opened || smbClient != null || sshClient != null) close()
        fileLength = C.LENGTH_UNSET.toLong()
        bytesRemaining = C.LENGTH_UNSET.toLong()
        position = 0L
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        try {
            val profileId = validateNetworkPlaybackUri(dataSpec.uri)
            val profile = profileStore.load().firstOrNull { it.id == profileId }
                ?: throw IOException("مصدر الشبكة لم يعد موجوداً")
            val expectedProtocol = networkProtocolForScheme(dataSpec.uri.scheme)
            require(profile.protocol == expectedProtocol) { "نوع المصدر لا يطابق رابط التشغيل" }
            val credentials = credentialVault.load(profile.id)
            ?: throw IOException("بيانات اعتماد مصدر الشبكة غير متاحة؛ أعد حفظ بيانات الدخول")
            val segments = dataSpec.uri.pathSegments
            require(dataSpec.position >= 0L) { "موضع القراءة غير صالح" }
            require(dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length > 0L) {
                "طول القراءة غير صالح"
            }

            when (profile.protocol) {
                NetworkProtocol.SMB -> openSmb(profile, credentials.first, credentials.second, segments)
                NetworkProtocol.SFTP -> openSftp(profile, credentials.first, credentials.second, segments)
                NetworkProtocol.WEBDAV -> throw IOException("مصدر WebDAV يستخدم مسار HTTP")
            }

            if (dataSpec.position > fileLength) throw EOFException("موضع القراءة يتجاوز حجم الملف")
            position = dataSpec.position
            bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                (fileLength - position).coerceAtLeast(0L)
            } else {
                minOf((fileLength - position).coerceAtLeast(0L), dataSpec.length)
            }
            opened = true
            transferStarted(dataSpec)
            return bytesRemaining
        } catch (error: Exception) {
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
        val client = NetworkClientTimeouts.createSmbClient()
        smbClient = client
        val connection = client.connect(profile.host, profile.port)
        smbConnection = connection
        val passwordChars = password.toCharArray()
        val session = try {
            connection.authenticate(AuthenticationContext(username, passwordChars, ""))
        } finally {
            passwordChars.fill('\u0000')
        }
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
        NetworkClientTimeouts.configureSsh(ssh)
        sshClient = ssh
        ssh.loadKnownHosts(knownHosts)
        ssh.connect(profile.host, profile.port)
        val passwordChars = password.toCharArray()
        try {
            ssh.authPassword(username, passwordChars)
        } finally {
            passwordChars.fill('\u0000')
        }
        val sftp = ssh.newSFTPClient()
        sftpClient = sftp
        val remotePath = "/" + segments.joinToString("/")
        val file = sftp.open(remotePath)
        sftpFile = file
        fileLength = file.length()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size || length > buffer.size - offset) {
            throw IndexOutOfBoundsException("حدود مخزن القراءة غير صالحة")
        }
        if (length == 0) return 0
        if (!opened) throw IOException("مصدر الشبكة غير مفتوح")
        if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT
        val requested = minOf(length.toLong(), bytesRemaining, MAX_READ_BYTES.toLong()).toInt()
        val count = try {
            readWithReconnect(
                read = {
                    validateNetworkReadCount(readNetworkChunk {
                        when {
                            smbFile != null -> smbFile!!.read(buffer, position, offset, requested)
                            sftpFile != null -> sftpFile!!.read(position, buffer, offset, requested)
                            else -> throw IOException("اتصال الشبكة غير جاهز")
                        }
                    }, requested)
                },
                reconnect = {
                    try {
                        reconnectAtCurrentPosition()
                    } catch (error: Exception) {
                        closeRemoteConnections()
                        throw IOException("تعذرت إعادة فتح مصدر الشبكة", error)
                    }
                }
            )
        } catch (error: IOException) {
            throw IOException("انقطع اتصال مصدر الشبكة وتعذرت استعادة القراءة", error)
        }
        position += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    /** Reopens the same remote file at the unchanged random-access position once. */
    private fun reconnectAtCurrentPosition() {
        val uri = currentUri ?: throw IOException("رابط مصدر الشبكة غير متاح لإعادة الاتصال")
        val profileId = validateNetworkPlaybackUri(uri)
        val profile = profileStore.load().firstOrNull { it.id == profileId }
            ?: throw IOException("مصدر الشبكة لم يعد موجوداً")
        val expectedProtocol = networkProtocolForScheme(uri.scheme)
        if (profile.protocol != expectedProtocol) {
            throw IOException("نوع مصدر الشبكة لا يطابق رابط التشغيل أثناء إعادة الاتصال")
        }
        val segments = uri.pathSegments
        val credentials = credentialVault.load(profile.id)
            ?: throw IOException("بيانات اعتماد مصدر الشبكة غير متاحة؛ أعد حفظ بيانات الدخول")
        closeRemoteConnections()
        when (profile.protocol) {
            NetworkProtocol.SMB -> openSmb(profile, credentials.first, credentials.second, segments)
            NetworkProtocol.SFTP -> openSftp(profile, credentials.first, credentials.second, segments)
            NetworkProtocol.WEBDAV -> throw IOException("مصدر WebDAV لا يستخدم هذا المسار")
        }
        if (position > fileLength) throw EOFException("تغير حجم الملف البعيد أثناء إعادة الاتصال")
    }

    override fun getUri(): Uri? = currentUri

    private fun closeRemoteConnections() {
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
    }

    override fun close() {
        closeRemoteConnections()
        currentUri = null
        fileLength = C.LENGTH_UNSET.toLong()
        bytesRemaining = C.LENGTH_UNSET.toLong()
        position = 0L
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    private companion object {
        const val MAX_READ_BYTES = 256 * 1024
    }
}

/** Resolve only the two private playback schemes used by saved SMB/SFTP profiles. */
internal fun networkProtocolForScheme(scheme: String?): NetworkProtocol = when (scheme?.lowercase()) {
    "maleksmb" -> NetworkProtocol.SMB
    "maleksftp" -> NetworkProtocol.SFTP
    else -> throw IOException("بروتوكول تشغيل الشبكة غير مدعوم")
}

/**
 * A known-length remote file must not silently end before its advertised size.
 * Media3 otherwise treats a premature EOF as a successful, truncated stream.
 */
internal fun validateNetworkReadCount(count: Int, requested: Int): Int {
    if (requested <= 0) throw IOException("حجم القراءة المطلوب غير صالح")
    if (count < 0) throw EOFException("انتهى ملف الشبكة قبل اكتمال القراءة")
    if (count == 0) throw IOException("مصدر الشبكة لم يحقق أي تقدم في القراءة")
    if (count > requested) throw IOException("مصدر الشبكة أعاد حجماً أكبر من المطلوب")
    return count
}

/** Retry transient zero-byte responses without returning a no-progress read. */
internal fun readNetworkChunk(read: () -> Int): Int {
    repeat(3) {
        val count = read()
        if (count != 0) return count
    }
    throw IOException("مصدر الشبكة لم يُرجع بيانات بعد عدة محاولات")
}

/**
 * Retries one failed random-access read after the caller reopens the remote file
 * at the same position. Only I/O failures are retried; programming errors and
 * cancellation signals are allowed to propagate unchanged.
 */
internal fun readWithReconnect(
    read: () -> Int,
    reconnect: () -> Unit
): Int {
    return try {
        read()
    } catch (error: IOException) {
        reconnect()
        read()
    }
}

/**
 * Chooses the remote protocol from the MediaItem Uri at open time. The wrapper
 * owns Media3 transfer callbacks while each delegate owns its protocol handles.
 */
@UnstableApi
class NetworkRoutingDataSource(
    private val context: Context,
    private val profileStore: NetworkProfileStore,
    private val credentialVault: NetworkCredentialVault,
    private val httpFactory: DataSource.Factory
) : BaseDataSource(true) {
    private var delegate: DataSource? = null
    private var currentUri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        if (delegate != null || opened || currentUri != null) close()
        transferInitializing(dataSpec)
        currentUri = dataSpec.uri
        val source = when (dataSpec.uri.scheme?.lowercase()) {
            "maleksmb", "maleksftp" -> NetworkMediaDataSource(context, profileStore, credentialVault)
            else -> httpFactory.createDataSource()
        }
        delegate = source
        return try {
            val length = source.open(dataSpec)
            opened = true
            transferStarted(dataSpec)
            length
        } catch (error: Throwable) {
            runCatching { source.close() }
            delegate = null
            currentUri = null
            throw error
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val source = delegate ?: throw IOException("مصدر التشغيل غير مفتوح")
        val count = source.read(buffer, offset, length)
        if (count > 0) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = delegate?.uri ?: currentUri

    override fun close() {
        val source = delegate
        delegate = null
        currentUri = null
        try {
            source?.close()
        } finally {
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
