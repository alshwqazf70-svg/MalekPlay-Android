package com.malekplay.safety

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN verifier with salted PBKDF2 hashes and a short lockout after repeated failures. */
class ParentalControlManager(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("malekplay_family", Context.MODE_PRIVATE)

    val isEnabled: Boolean get() = prefs.getBoolean(KEY_ENABLED, false)
    val hasPin: Boolean get() = prefs.contains(KEY_HASH) && prefs.contains(KEY_SALT)

    fun setPin(pin: String) {
        require(pin.matches(Regex("[0-9]{4,8}"))) { "يجب أن يتكون رمز PIN من 4 إلى 8 أرقام" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        check(prefs.edit()
            .putString(KEY_SALT, salt.toHex())
            .putString(KEY_HASH, hash.toHex())
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .commit())
    }

    fun verifyPin(pin: String): Boolean {
        val now = System.currentTimeMillis()
        if (now < prefs.getLong(KEY_LOCK_UNTIL, 0L)) return false
        val salt = prefs.getString(KEY_SALT, null)?.hexToBytes() ?: return false
        val expected = prefs.getString(KEY_HASH, null)?.hexToBytes() ?: return false
        val actual = derive(pin, salt)
        val valid = MessageDigest.isEqual(expected, actual)
        if (valid) {
            prefs.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
        } else {
            val failures = prefs.getInt(KEY_FAILURES, 0) + 1
            val lockUntil = if (failures >= MAX_FAILURES) now + LOCKOUT_MS else 0L
            prefs.edit().putInt(KEY_FAILURES, if (lockUntil > 0) 0 else failures)
                .putLong(KEY_LOCK_UNTIL, lockUntil).apply()
        }
        return valid
    }

    fun enableAfterPin(pin: String): Boolean {
        if (!verifyPin(pin)) return false
        prefs.edit().putBoolean(KEY_ENABLED, true).apply()
        return true
    }

    fun disableAfterPin(pin: String): Boolean {
        if (!verifyPin(pin)) return false
        prefs.edit().putBoolean(KEY_ENABLED, false).apply()
        return true
    }

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0 || !matches(Regex("[0-9a-fA-F]*"))) return null
        return runCatching { chunked(2).map { it.toInt(16).toByte() }.toByteArray() }.getOrNull()
    }

    private companion object {
        const val KEY_ENABLED = "child_mode_enabled"
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val KEY_FAILURES = "pin_failures"
        const val KEY_LOCK_UNTIL = "pin_lock_until"
        const val PBKDF2_ITERATIONS = 120_000
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 60_000L
    }
}
