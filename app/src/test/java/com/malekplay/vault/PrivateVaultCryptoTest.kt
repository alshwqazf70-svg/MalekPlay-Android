package com.malekplay.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class PrivateVaultCryptoTest {
    private val password = "correct horse battery".toCharArray()

    @Test
    fun encryptDecryptPreservesBinaryPayload() {
        val source = ByteArray(180_000) { ((it * 31) xor (it ushr 3)).toByte() }
        val encrypted = ByteArrayOutputStream()
        PrivateVaultCrypto.encrypt(ByteArrayInputStream(source), encrypted, password)

        val restored = ByteArrayOutputStream()
        PrivateVaultCrypto.decrypt(ByteArrayInputStream(encrypted.toByteArray()), restored, password)

        assertArrayEquals(source, restored.toByteArray())
    }

    @Test
    fun wrongPasswordFailsAuthentication() {
        val encrypted = ByteArrayOutputStream()
        PrivateVaultCrypto.encrypt(ByteArrayInputStream("private".toByteArray()), encrypted, password)

        assertThrows(Exception::class.java) {
            PrivateVaultCrypto.decrypt(
                ByteArrayInputStream(encrypted.toByteArray()),
                ByteArrayOutputStream(),
                "incorrect password".toCharArray()
            )
        }
    }

    @Test
    fun modifiedCiphertextFailsAuthentication() {
        val encrypted = ByteArrayOutputStream()
        PrivateVaultCrypto.encrypt(ByteArrayInputStream(ByteArray(128) { it.toByte() }), encrypted, password)
        val damaged = encrypted.toByteArray().also { it[it.lastIndex - 1] = (it[it.lastIndex - 1].toInt() xor 1).toByte() }

        assertThrows(Exception::class.java) {
            PrivateVaultCrypto.decrypt(ByteArrayInputStream(damaged), ByteArrayOutputStream(), password)
        }
    }

    @Test
    fun rejectsShortPassword() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivateVaultCrypto.encrypt(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), "short".toCharArray())
        }
    }

    @Test
    fun chunkedV2EncryptSeekReadAndDecryptPreservesMultiChunkPayload() {
        val key = PrivateVaultCrypto.generateMasterKey()
        val source = ByteArray(150_000) { ((it * 29) xor (it ushr 4)).toByte() }
        val file = Files.createTempFile("vault-v2-", ".mkv").toFile()
        try {
            val written = PrivateVaultCrypto.encryptFileChunked(ByteArrayInputStream(source), file, key)
            assertEquals(source.size.toLong(), written)

            PrivateVaultCrypto.VaultSeekableReader(file, key).use { reader ->
                assertEquals(source.size.toLong(), reader.plaintextSize)
                // Read spanning chunk 0 -> chunk 1 boundary (65536)
                val slice = ByteArray(5000)
                var pos = 63_000L
                var filled = 0
                while (filled < slice.size) {
                    val read = reader.readAt(pos + filled, slice, filled, slice.size - filled)
                    assert(read > 0)
                    filled += read
                }
                assertArrayEquals(source.copyOfRange(63_000, 68_000), slice)

                // Read tail
                val tail = ByteArray(200)
                val tailRead = reader.readAt(source.size - 200L, tail, 0, tail.size)
                assertEquals(200, tailRead)
                assertArrayEquals(source.copyOfRange(source.size - 200, source.size), tail)
            }

            val fullOut = ByteArrayOutputStream()
            PrivateVaultCrypto.decryptStreamAuto(file, fullOut, key, null)
            assertArrayEquals(source, fullOut.toByteArray())
        } finally {
            key.fill(0)
            file.delete()
        }
    }

    @Test
    fun chunkedV2TamperedOrTruncatedFileFailsAuthentication() {
        val key = PrivateVaultCrypto.generateMasterKey()
        val source = ByteArray(90_000) { it.toByte() }
        val file = Files.createTempFile("vault-v2-tamper-", ".mkv").toFile()
        try {
            PrivateVaultCrypto.encryptFileChunked(ByteArrayInputStream(source), file, key)
            val bytes = file.readBytes()
            bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0xFF).toByte()
            file.writeBytes(bytes)

            assertThrows(Exception::class.java) {
                PrivateVaultCrypto.decryptStreamAuto(file, ByteArrayOutputStream(), key, null)
            }

            // Truncated file rejected on reader open
            file.writeBytes(bytes.copyOf(bytes.size - 10))
            assertThrows(IllegalArgumentException::class.java) {
                PrivateVaultCrypto.VaultSeekableReader(file, key)
            }
        } finally {
            key.fill(0)
            file.delete()
        }
    }

    @Test
    fun chunkedV2EmptyFileRoundTrip() {
        val key = PrivateVaultCrypto.generateMasterKey()
        val file = Files.createTempFile("vault-v2-empty-", ".mkv").toFile()
        try {
            val written = PrivateVaultCrypto.encryptFileChunked(ByteArrayInputStream(ByteArray(0)), file, key)
            assertEquals(0L, written)
            val out = ByteArrayOutputStream()
            PrivateVaultCrypto.decryptStreamAuto(file, out, key, null)
            assertEquals(0, out.size())
        } finally {
            key.fill(0)
            file.delete()
        }
    }

    @Test
    fun keyBlobV3EncryptDecryptAndWrongKeyFails() {
        val key = PrivateVaultCrypto.generateMasterKey()
        val wrongKey = PrivateVaultCrypto.generateMasterKey()
        val plain = "vault-index-metadata".toByteArray()
        try {
            val encrypted = PrivateVaultCrypto.encryptWithKey(plain, key)
            assertArrayEquals(plain, PrivateVaultCrypto.decryptWithKey(encrypted, key))
            assertThrows(Exception::class.java) {
                PrivateVaultCrypto.decryptWithKey(encrypted, wrongKey)
            }
        } finally {
            key.fill(0)
            wrongKey.fill(0)
        }
    }
}
