package com.malekplay.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts remote server credentials with an AES-256 key held by Android Keystore.
 * The key is non-exportable and credentials are never stored in connection URLs.
 */
class NetworkCredentialVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "malekplay_network_secrets", Context.MODE_PRIVATE
    )

    fun save(profileId: String, username: String, password: String) {
        require(profileId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        val plain = (username + "\u0000" + password).toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain)
        val packed = cipher.iv + encrypted
        check(preferences.edit().putString(profileId, Base64.encodeToString(packed, Base64.NO_WRAP)).commit())
    }

    fun load(profileId: String): Pair<String, String>? = runCatching {
        if (!profileId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) return@runCatching null
        val packed = preferences.getString(profileId, null)
            ?.let { Base64.decode(it, Base64.NO_WRAP) }
            ?: return@runCatching null
        if (packed.size <= IV_LENGTH + 16) return@runCatching null

        val iv = packed.copyOfRange(0, IV_LENGTH)
        val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val decoded = String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
        val separator = decoded.indexOf('\\u0000')
        if (separator < 0) return@runCatching null
        decoded.substring(0, separator) to decoded.substring(separator + 1)
    }.getOrNull()

    fun remove(profileId: String) {
        preferences.edit().remove(profileId).apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "malekplay.network.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
    }
}
