package com.malekplay.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
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
        synchronized(STORAGE_LOCK) {
            require(profileId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                "Invalid network profile id"
            }
            validateNetworkCredentials(username, password)
            val plain = (username + "\u0000" + password).toByteArray(StandardCharsets.UTF_8)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val encrypted = try {
                cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
                cipher.updateAAD(profileId.toByteArray(StandardCharsets.UTF_8))
                cipher.doFinal(plain)
            } finally {
                // Clear the temporary plaintext buffer as soon as encryption finishes.
                plain.fill(0)
            }
            // Bind ciphertext to its profile so encrypted records cannot be swapped.
            val iv = cipher.iv
            val packed = FORMAT_MARKER + iv + encrypted
            try {
                check(
                    preferences.edit()
                        .putString(profileId, Base64.encodeToString(packed, Base64.NO_WRAP))
                        .commit()
                ) { "Could not save network credentials" }
            } finally {
                // Clear all temporary binary copies after Base64 persistence.
                packed.fill(0)
                iv.fill(0)
                encrypted.fill(0)
            }
        }
    }

    /**
     * Returns null only when no credentials were saved. Corrupt ciphertext,
     * invalidated Keystore keys and authentication failures are surfaced to callers.
     */
    fun load(profileId: String): Pair<String, String>? = synchronized(STORAGE_LOCK) {
        require(profileId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "Invalid network profile id"
        }
        val encoded = preferences.getString(profileId, null) ?: return@synchronized null
        val packed = try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (error: IllegalArgumentException) {
            throw IllegalStateException("Stored network credentials are corrupted", error)
        }
        try {
            val marker = if (packed.size >= FORMAT_MARKER.size) {
                packed.copyOfRange(0, FORMAT_MARKER.size)
            } else {
                ByteArray(0)
            }
            val hasMarker = try {
                MessageDigest.isEqual(marker, FORMAT_MARKER)
            } finally {
                marker.fill(0)
            }
            val offset = if (hasMarker) FORMAT_MARKER.size else 0
            require(packed.size > offset + IV_LENGTH + 16) {
                "Stored network credentials are incomplete"
            }

            val iv = packed.copyOfRange(offset, offset + IV_LENGTH)
            val ciphertext = packed.copyOfRange(offset + IV_LENGTH, packed.size)
            try {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
                if (hasMarker) cipher.updateAAD(profileId.toByteArray(StandardCharsets.UTF_8))
                val decodedBytes = cipher.doFinal(ciphertext)
                try {
                    // Split on the binary delimiter before creating immutable Strings;
                    // avoid constructing an extra combined plaintext String in memory.
                    val separator = decodedBytes.indexOf(0)
                    check(separator >= 0) { "Stored network credentials have an invalid format" }
                    val credentials = String(
                        decodedBytes, 0, separator, StandardCharsets.UTF_8
                    ) to String(
                        decodedBytes, separator + 1,
                        decodedBytes.size - separator - 1, StandardCharsets.UTF_8
                    )
                    if (!hasMarker) save(profileId, credentials.first, credentials.second)
                    credentials
                } finally {
                    decodedBytes.fill(0)
                }
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
        } finally {
            packed.fill(0)
        }
    }

    fun remove(profileId: String) {
        synchronized(STORAGE_LOCK) {
            require(profileId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
                "Invalid network profile id"
            }
            check(preferences.edit().remove(profileId).commit()) {
                "Could not remove stored network credentials"
            }
        }
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
        // Multiple vault instances share the same preferences and Keystore alias.
        // A process-wide lock prevents concurrent key creation and credential writes.
        val STORAGE_LOCK = Any()
        const val KEY_ALIAS = "malekplay.network.credentials.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        val FORMAT_MARKER = byteArrayOf(0x4D, 0x50, 0x01)
    }
}
