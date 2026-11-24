package com.malekplay.vault

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Streaming authenticated encryption. Decrypt only into a private temporary file;
 * publish it only after this method returns and GCM authentication has succeeded. */
internal object PrivateVaultCrypto {
    private const val MAGIC = 0x4D4B5654 // MKVT
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val BUFFER_BYTES = 64 * 1024
    private val random = SecureRandom()

    fun encrypt(input: InputStream, output: OutputStream, password: CharArray) {
        require(password.size >= 8) { "Vault password must contain at least 8 characters." }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val header = header(salt, iv, ITERATIONS)
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
        val iterations = data.readInt()
        require(magic == MAGIC && version == VERSION) { "Unsupported private-vault file." }
        require(iterations in 100_000..1_000_000) { "Invalid key-derivation parameters." }
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        val iv = ByteArray(IV_BYTES).also(data::readFully)
        val header = header(salt, iv, iterations)
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
            output.write(cipher.doFinal()) // Authentication failure is fatal.
            output.flush()
        } finally {
            buffer.fill(0); key.fill(0); salt.fill(0); iv.fill(0); header.fill(0)
        }
    }

    private fun header(salt: ByteArray, iv: ByteArray, iterations: Int): ByteArray {
        val bytes = java.io.ByteArrayOutputStream(4 + 1 + 4 + SALT_BYTES + IV_BYTES)
        DataOutputStream(bytes).use {
            it.writeInt(MAGIC); it.writeByte(VERSION); it.writeInt(iterations)
            it.write(salt); it.write(iv)
        }
        return bytes.toByteArray()
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
