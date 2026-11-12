package com.malekplay.safety

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Provider-independent PBKDF2-HMAC-SHA256 (RFC 8018).
 *
 * Android 10 and some OEM crypto providers do not register the
 * "PBKDF2WithHmacSHA256" SecretKeyFactory. HmacSHA256 is available on the
 * supported Android API range, so deriving the blocks with Mac avoids that
 * provider lookup while retaining byte-for-byte compatibility with existing
 * PBKDF2-HMAC-SHA256 PIN verifiers.
 */
internal object Pbkdf2HmacSha256 {
    fun derive(password: CharArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
        require(password.isNotEmpty()) { "Password must not be empty." }
        require(salt.isNotEmpty()) { "Salt must not be empty." }
        require(iterations > 0) { "PBKDF2 iteration count must be positive." }
        require(keyLengthBytes > 0) { "Derived key length must be positive." }

        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(passwordBytes, "HmacSHA256"))
        val hLen = mac.macLength
        val blockCount = (keyLengthBytes + hLen - 1) / hLen
        require(blockCount.toLong() <= 0xFFFFFFFFL) { "Derived key is too long." }
        val result = ByteArray(keyLengthBytes)
        val blockInput = ByteArray(salt.size + 4)
        salt.copyInto(blockInput)
        var outputOffset = 0
        try {
            for (block in 1..blockCount) {
                blockInput[blockInput.lastIndex - 3] = (block ushr 24).toByte()
                blockInput[blockInput.lastIndex - 2] = (block ushr 16).toByte()
                blockInput[blockInput.lastIndex - 1] = (block ushr 8).toByte()
                blockInput[blockInput.lastIndex] = block.toByte()
                var u = mac.doFinal(blockInput)
                val t = u.copyOf()
                try {
                    repeat(iterations - 1) {
                        val next = mac.doFinal(u)
                        for (index in t.indices) {
                            t[index] = (t[index].toInt() xor next[index].toInt()).toByte()
                        }
                        u.fill(0)
                        u = next
                    }
                    val count = minOf(hLen, keyLengthBytes - outputOffset)
                    t.copyInto(result, outputOffset, 0, count)
                    outputOffset += count
                } finally {
                    u.fill(0)
                    t.fill(0)
                }
            }
            return result
        } catch (failure: Throwable) {
            result.fill(0)
            throw failure
        } finally {
            passwordBytes.fill(0)
            blockInput.fill(0)
        }
    }
}
