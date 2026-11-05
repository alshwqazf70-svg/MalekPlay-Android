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

    fun setPin(pin: String) = synchronized(PIN_LOCK) {
        require(isValidPin(pin)) { "يجب أن يتكون رمز PIN من 4 إلى 8 أرقام" }
        val salt = ByteArray(SALT_LENGTH).also(SecureRandom()::nextBytes)
        val hash = derive(pin, salt)
        try {
            check(prefs.edit()
                .putString(KEY_SALT, salt.toHex())
                .putString(KEY_HASH, hash.toHex())
                .putInt(KEY_FAILURES, 0)
                .putLong(KEY_LOCK_UNTIL, 0L)
                .commit()) { "تعذر حفظ إعدادات رمز PIN" }
        } finally {
            salt.fill(0)
            hash.fill(0)
        }
    }

    fun verifyPin(pin: String): Boolean = synchronized(PIN_LOCK) {
        if (!isValidPin(pin)) return@synchronized false
        val now = System.currentTimeMillis()
        if (now < prefs.getLong(KEY_LOCK_UNTIL, 0L)) return@synchronized false

        val salt = prefs.getString(KEY_SALT, null)?.hexToBytes()
            ?.takeIf { it.size == SALT_LENGTH } ?: return@synchronized false
        val expected = prefs.getString(KEY_HASH, null)?.hexToBytes()
            ?.takeIf { it.size == HASH_LENGTH } ?: return@synchronized false
        val actual = try {
            derive(pin, salt)
        } finally {
            salt.fill(0)
        }
        val valid = try {
            MessageDigest.isEqual(expected, actual)
        } finally {
            expected.fill(0)
            actual.fill(0)
        }

        val editor = if (valid) {
            prefs.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LOCK_UNTIL, 0L)
        } else {
            val failures = prefs.getInt(KEY_FAILURES, 0) + 1
            val lockUntil = if (failures >= MAX_FAILURES) now + LOCKOUT_MS else 0L
            prefs.edit()
                .putInt(KEY_FAILURES, if (lockUntil > 0) 0 else failures)
                .putLong(KEY_LOCK_UNTIL, lockUntil)
        }
        check(editor.commit()) { "تعذر تحديث حالة رمز PIN" }
        valid
    }

    fun enableAfterPin(pin: String): Boolean = synchronized(PIN_LOCK) {
        if (!verifyPin(pin)) return@synchronized false
        prefs.edit().putBoolean(KEY_ENABLED, true).commit()
    }

    fun disableAfterPin(pin: String): Boolean = synchronized(PIN_LOCK) {
        if (!verifyPin(pin)) return@synchronized false
        prefs.edit().putBoolean(KEY_ENABLED, false).commit()
    }

    private fun isValidPin(pin: String): Boolean = pin.matches(Regex("[0-9]{4,8}"))

    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val chars = pin.toCharArray()
        val spec = PBEKeySpec(chars, salt, PBKDF2_ITERATIONS, HASH_LENGTH * 8)
        chars.fill('\u0000')
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
        val PIN_LOCK = Any()
        const val KEY_ENABLED = "child_mode_enabled"
        const val KEY_SALT = "pin_salt"
        const val KEY_HASH = "pin_hash"
        const val KEY_FAILURES = "pin_failures"
        const val KEY_LOCK_UNTIL = "pin_lock_until"
        const val SALT_LENGTH = 16
        const val HASH_LENGTH = 32
        const val PBKDF2_ITERATIONS = 120_000
        const val MAX_FAILURES = 5
        const val LOCKOUT_MS = 60_000L
    }
}
