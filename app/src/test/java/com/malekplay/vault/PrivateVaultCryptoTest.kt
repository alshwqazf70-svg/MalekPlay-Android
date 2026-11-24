package com.malekplay.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

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
}
