package com.malekplay.vault

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Authenticated encryption primitives for the Private Vault.
 *
 * Formats:
 * - Version 1: Legacy single-stream PBKDF2 + AES-256-GCM (kept for backwards compatibility).
 * - Version 2: Seekable 64 KB chunked AES-256-GCM keyed with a 256-bit Master Key (DEK),
 *   supporting O(1) memory streaming and random-access media playback.
 * - Version 3: Small in-memory AES-256-GCM blobs keyed with the 256-bit Master Key (index/thumbs).
 */
internal object PrivateVaultCrypto {
    const val MAGIC = 0x4D4B5654 // MKVT
    private const val VERSION_V1 = 1
    private const val VERSION_CHUNKED_V2 = 2
    private const val VERSION_KEY_BLOB_V3 = 3

    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val KEY_BYTES = 32
    private const val TAG_BITS = 128
    private const val TAG_BYTES = 16
    private const val BUFFER_BYTES = 64 * 1024
    const val DEFAULT_CHUNK_SIZE = 64 * 1024
    private const val HEADER_PREFIX_V2_BYTES = 4 + 1 + 4 + IV_BYTES // 21 bytes
    private const val HEADER_V2_BYTES = HEADER_PREFIX_V2_BYTES + 8 // 29 bytes
    private val random = SecureRandom()

    fun generateMasterKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    // ------------------------------------------------------------------ V1 legacy password stream

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.size >= 8) { "Vault password must contain at least 8 characters." }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = headerV1(salt, iv, ITERATIONS)
        val key = deriveKey(password, salt, ITERATIONS)
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            val data = DataOutputStream(output)
            data.write(header)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                cipher.update(buffer, 0, count)?.takeIf { it.isNotEmpty() }?.let(data::write)
            }
            data.write(cipher.doFinal())
            data.flush()
        } finally {
            buffer.fill(0); key.fill(0); salt.fill(0); iv.fill(0); header.fill(0)
        }
    }

    fun decrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.size >= 8) { "Vault password must contain at least 8 characters." }
        val data = DataInputStream(input)
        val magic = data.readInt()
        val version = data.readUnsignedByte()
        require(magic == MAGIC && version == VERSION_V1) { "Unsupported private-vault file." }
        decryptV1Body(data, output, password)
    }

    private fun decryptV1Body(data: DataInputStream, output: OutputStream, password: CharArray) {
        val iterations = data.readInt()
        require(iterations in 100_000..1_000_000) { "Invalid key-derivation parameters." }
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        val iv = ByteArray(IV_BYTES).also(data::readFully)
        val header = headerV1(salt, iv, iterations)
        val key = deriveKey(password, salt, iterations)
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            while (true) {
                val count = data.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                cipher.update(buffer, 0, count)?.takeIf { it.isNotEmpty() }?.let(output::write)
            }
            output.write(cipher.doFinal())
            output.flush()
        } finally {
            buffer.fill(0); key.fill(0); salt.fill(0); iv.fill(0); header.fill(0)
        }
    }

    // ------------------------------------------------------------------ V3 raw-key small blobs

    fun encryptWithKey(plain: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Invalid master key size." }
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = ByteArray(4 + 1 + IV_BYTES)
        ByteBuffer.wrap(header).apply {
            putInt(MAGIC)
            put(VERSION_KEY_BLOB_V3.toByte())
            put(iv)
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            val encrypted = cipher.doFinal(plain)
            ByteArray(header.size + encrypted.size).also { out ->
                header.copyInto(out, 0)
                encrypted.copyInto(out, header.size)
            }
        } finally {
            iv.fill(0)
            header.fill(0)
        }
    }

    fun decryptWithKey(payload: ByteArray, key: ByteArray): ByteArray {
        require(key.size == KEY_BYTES) { "Invalid master key size." }
        require(payload.size >= 4 + 1 + IV_BYTES + TAG_BYTES) { "Corrupt vault blob." }
        val buf = ByteBuffer.wrap(payload)
        val magic = buf.int
        val version = buf.get().toInt() and 0xFF
        require(magic == MAGIC && version == VERSION_KEY_BLOB_V3) { "Unsupported vault key blob." }
        val iv = ByteArray(IV_BYTES).also(buf::get)
        val header = payload.copyOfRange(0, 4 + 1 + IV_BYTES)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            cipher.doFinal(payload, header.size, payload.size - header.size)
        } finally {
            iv.fill(0)
            header.fill(0)
        }
    }

    // ------------------------------------------------------------------ V2 chunked seekable AEAD

    fun encryptFileChunked(
        input: InputStream,
        destinationFile: File,
        key: ByteArray,
        chunkSize: Int = DEFAULT_CHUNK_SIZE,
        onBytesProcessed: ((Long) -> Unit)? = null
    ): Long {
        require(key.size == KEY_BYTES) { "Invalid master key size." }
        require(chunkSize in 4096..(1024 * 1024)) { "Invalid chunk size." }
        val baseIv = ByteArray(IV_BYTES).also(random::nextBytes)
        val headerPrefix = headerPrefixV2(chunkSize, baseIv)
        val chunkBuffer = ByteArray(chunkSize)
        val nextProbe = ByteArray(1)
        val keySpec = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        var totalPlainBytes = 0L

        RandomAccessFile(destinationFile, "rw").use { raf ->
            raf.setLength(0L)
            raf.write(headerPrefix)
            raf.writeLong(0L) // Placeholder for plaintextSize, patched after EOF

            var chunkIndex = 0L
            var pendingFirstByte = -1

            while (true) {
                var filled = 0
                if (pendingFirstByte >= 0) {
                    chunkBuffer[0] = pendingFirstByte.toByte()
                    filled = 1
                    pendingFirstByte = -1
                }
                while (filled < chunkSize) {
                    val read = input.read(chunkBuffer, filled, chunkSize - filled)
                    if (read < 0) break
                    if (read == 0) continue
                    filled += read
                }

                // Peek 1 byte when the buffer is full so we know accurately whether this chunk is final.
                val isFinal: Boolean
                if (filled == chunkSize) {
                    var probeCount = input.read(nextProbe)
                    while (probeCount == 0) probeCount = input.read(nextProbe)
                    if (probeCount < 0) {
                        isFinal = true
                    } else {
                        isFinal = false
                        pendingFirstByte = nextProbe[0].toInt() and 0xFF
                    }
                } else {
                    isFinal = true
                }

                val nonce = deriveChunkNonce(baseIv, chunkIndex)
                val aad = chunkAadV2(headerPrefix, chunkIndex, isFinal)
                cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
                cipher.updateAAD(aad)
                val encryptedChunk = cipher.doFinal(chunkBuffer, 0, filled)
                raf.write(encryptedChunk)

                totalPlainBytes += filled.toLong()
                onBytesProcessed?.invoke(totalPlainBytes)
                chunkIndex++
                if (isFinal) break
            }

            raf.seek(HEADER_PREFIX_V2_BYTES.toLong())
            raf.writeLong(totalPlainBytes)
            raf.fd.sync()
        }

        chunkBuffer.fill(0)
        baseIv.fill(0)
        headerPrefix.fill(0)
        return totalPlainBytes
    }

    fun decryptStreamAuto(
        sourceFile: File,
        output: OutputStream,
        key: ByteArray?,
        password: CharArray?,
        onBytesProcessed: ((Long) -> Unit)? = null
    ) {
        val version = sourceFile.inputStream().use { input ->
            val data = DataInputStream(input)
            val magic = data.readInt()
            require(magic == MAGIC) { "Unsupported private-vault file." }
            data.readUnsignedByte()
        }
        when (version) {
            VERSION_CHUNKED_V2 -> {
                val masterKey = requireNotNull(key) { "Master key required for v2 vault entry." }
                VaultSeekableReader(sourceFile, masterKey).use { reader ->
                    val buffer = ByteArray(reader.chunkSize)
                    var pos = 0L
                    try {
                        while (pos < reader.plaintextSize) {
                            val read = reader.readAt(pos, buffer, 0, buffer.size)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            pos += read.toLong()
                            onBytesProcessed?.invoke(pos)
                        }
                        if (reader.plaintextSize == 0L) {
                            reader.verifyEmpty()
                        }
                        output.flush()
                    } finally {
                        buffer.fill(0)
                    }
                }
            }
            VERSION_V1 -> {
                val pwd = requireNotNull(password) { "Password required for legacy v1 vault entry." }
                sourceFile.inputStream().buffered().use { input ->
                    decrypt(input, output, pwd)
                }
                onBytesProcessed?.invoke(sourceFile.length())
            }
            VERSION_KEY_BLOB_V3 -> {
                val masterKey = requireNotNull(key) { "Master key required for vault blob." }
                val plain = decryptWithKey(sourceFile.readBytes(), masterKey)
                try {
                    output.write(plain)
                    output.flush()
                    onBytesProcessed?.invoke(plain.size.toLong())
                } finally {
                    plain.fill(0)
                }
            }
            else -> throw IllegalArgumentException("Unsupported private-vault version: $version")
        }
    }

    fun isChunkedV2File(file: File): Boolean {
        if (!file.isFile || file.length() < HEADER_V2_BYTES) return false
        return runCatching {
            file.inputStream().use { input ->
                val data = DataInputStream(input)
                data.readInt() == MAGIC && data.readUnsignedByte() == VERSION_CHUNKED_V2
            }
        }.getOrDefault(false)
    }

    internal class VaultSeekableReader(
        file: File,
        key: ByteArray
    ) : Closeable {
        private val raf = RandomAccessFile(file, "r")
        private val keySpec = SecretKeySpec(key, "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val headerPrefix: ByteArray
        private val baseIv: ByteArray
        val chunkSize: Int
        val plaintextSize: Long
        private val totalChunks: Long
        private val encryptedFullChunkBytes: Int

        private var cachedChunkIndex = -1L
        private var cachedChunkPlain = ByteArray(0)

        init {
            require(key.size == KEY_BYTES) { "Invalid master key size." }
            val fileLen = raf.length()
            require(fileLen >= HEADER_V2_BYTES + TAG_BYTES) { "Truncated v2 vault file." }
            val fullHeader = ByteArray(HEADER_V2_BYTES)
            raf.readFully(fullHeader)
            val buf = ByteBuffer.wrap(fullHeader)
            val magic = buf.int
            val version = buf.get().toInt() and 0xFF
            require(magic == MAGIC && version == VERSION_CHUNKED_V2) { "Invalid v2 vault header." }
            chunkSize = buf.int
            require(chunkSize in 4096..(1024 * 1024)) { "Invalid chunk size." }
            baseIv = ByteArray(IV_BYTES).also(buf::get)
            plaintextSize = buf.long
            require(plaintextSize >= 0L) { "Invalid plaintext length." }
            headerPrefix = fullHeader.copyOfRange(0, HEADER_PREFIX_V2_BYTES)
            encryptedFullChunkBytes = chunkSize + TAG_BYTES
            totalChunks = if (plaintextSize == 0L) 1L else (plaintextSize + chunkSize - 1L) / chunkSize

            val lastChunkPlainLen = if (plaintextSize == 0L) 0L else {
                val rem = plaintextSize % chunkSize
                if (rem == 0L) chunkSize.toLong() else rem
            }
            val expectedFileLen = HEADER_V2_BYTES.toLong() +
                (totalChunks - 1L) * encryptedFullChunkBytes.toLong() +
                lastChunkPlainLen + TAG_BYTES.toLong()
            require(fileLen == expectedFileLen) {
                "Corrupted or truncated v2 vault file (expected=$expectedFileLen, actual=$fileLen)."
            }
        }

        fun verifyEmpty() {
            if (plaintextSize == 0L) {
                loadChunk(0L)
            }
        }

        @Synchronized
        fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= plaintextSize) return -1
            if (length == 0) return 0
            val chunkIndex = position / chunkSize
            val chunkPlain = loadChunk(chunkIndex)
            val offsetInChunk = (position % chunkSize).toInt()
            val available = chunkPlain.size - offsetInChunk
            if (available <= 0) return -1
            val toCopy = minOf(length, available)
            chunkPlain.copyInto(buffer, destinationOffset = offset, startIndex = offsetInChunk, endIndex = offsetInChunk + toCopy)
            return toCopy
        }

        private fun loadChunk(chunkIndex: Long): ByteArray {
            if (chunkIndex == cachedChunkIndex) return cachedChunkPlain
            require(chunkIndex in 0 until totalChunks) { "Chunk index out of bounds: $chunkIndex" }
            val isFinal = chunkIndex == totalChunks - 1L
            val plainLen = if (plaintextSize == 0L) 0 else if (!isFinal) chunkSize else {
                val rem = (plaintextSize % chunkSize).toInt()
                if (rem == 0) chunkSize else rem
            }
            val encLen = plainLen + TAG_BYTES
            val fileOffset = HEADER_V2_BYTES.toLong() + chunkIndex * encryptedFullChunkBytes.toLong()
            val encBytes = ByteArray(encLen)
            raf.seek(fileOffset)
            raf.readFully(encBytes)

            val nonce = deriveChunkNonce(baseIv, chunkIndex)
            val aad = chunkAadV2(headerPrefix, chunkIndex, isFinal)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad)
            val plain = cipher.doFinal(encBytes)
            cachedChunkPlain.fill(0)
            cachedChunkIndex = chunkIndex
            cachedChunkPlain = plain
            return plain
        }

        override fun close() {
            cachedChunkPlain.fill(0)
            cachedChunkIndex = -1L
            baseIv.fill(0)
            headerPrefix.fill(0)
            raf.close()
        }
    }

    private fun headerV1(salt: ByteArray, iv: ByteArray, iterations: Int): ByteArray {
        val bytes = ByteArrayOutputStream(4 + 1 + 4 + SALT_BYTES + IV_BYTES)
        DataOutputStream(bytes).use {
            it.writeInt(MAGIC); it.writeByte(VERSION_V1); it.writeInt(iterations)
            it.write(salt); it.write(iv)
        }
        return bytes.toByteArray()
    }

    private fun headerPrefixV2(chunkSize: Int, baseIv: ByteArray): ByteArray {
        val bytes = ByteArray(HEADER_PREFIX_V2_BYTES)
        ByteBuffer.wrap(bytes).apply {
            putInt(MAGIC)
            put(VERSION_CHUNKED_V2.toByte())
            putInt(chunkSize)
            put(baseIv)
        }
        return bytes
    }

    private fun deriveChunkNonce(baseIv: ByteArray, chunkIndex: Long): ByteArray {
        val nonce = baseIv.copyOf()
        for (i in 0 until 8) {
            val shift = (7 - i) * 8
            val indexByte = ((chunkIndex ushr shift) and 0xFFL).toInt()
            nonce[4 + i] = (nonce[4 + i].toInt() xor indexByte).toByte()
        }
        return nonce
    }

    private fun chunkAadV2(headerPrefix: ByteArray, chunkIndex: Long, isFinal: Boolean): ByteArray {
        val aad = ByteArray(headerPrefix.size + 8 + 1)
        headerPrefix.copyInto(aad, 0)
        ByteBuffer.wrap(aad, headerPrefix.size, 9).apply {
            putLong(chunkIndex)
            put(if (isFinal) 1.toByte() else 0.toByte())
        }
        return aad
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val encoded = Charsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(password))
        val passwordBytes = ByteArray(encoded.remaining()).also(encoded::get)
        val blockInput = ByteArray(salt.size + 4)
        salt.copyInto(blockInput)
        blockInput[blockInput.lastIndex] = 1
        val result = ByteArray(32)
        var u = ByteArray(0)
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
            u = mac.doFinal(blockInput)
            u.copyInto(result)
            repeat(iterations - 1) {
                val next = mac.doFinal(u)
                for (i in result.indices) result[i] = (result[i].toInt() xor next[i].toInt()).toByte()
                u.fill(0); u = next
            }
            return result
        } catch (failure: Throwable) {
            result.fill(0); throw failure
        } finally {
            u.fill(0); passwordBytes.fill(0); blockInput.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }
}
