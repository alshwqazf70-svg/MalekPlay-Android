package com.malekplay.network

import android.content.Context

/**
 * Persists receiver-approved device identities, never network addresses or pairing secrets.
 *
 * A saved identity only skips the next consent prompt; every connection must still
 * prove possession of the current one-session QR/PIN through the authenticated MPC2
 * handshake. This prevents a remembered device ID from becoming a reusable credential.
 */
internal object UltraShareTrustedPeerStore {
    private const val PREFERENCES = "ultra_share_trusted_peers"
    private const val KEY_PEER_IDS = "approved_peer_ids"
    private const val MAX_TRUSTED_PEERS = 64

    fun isValidPeerId(value: String): Boolean =
        value.length == 32 && value.all { it.digitToIntOrNull(16) != null }

    @Synchronized
    fun isTrusted(context: Context, peerId: String): Boolean {
        if (!isValidPeerId(peerId)) return false
        return preferences(context).getStringSet(KEY_PEER_IDS, emptySet())
            ?.contains(peerId.lowercase()) == true
    }

    @Synchronized
    fun rememberApproved(context: Context, peerId: String): Boolean {
        if (!isValidPeerId(peerId)) return false
        val prefs = preferences(context)
        val ids = prefs.getStringSet(KEY_PEER_IDS, emptySet())
            .orEmpty()
            .filter(::isValidPeerId)
            .mapTo(LinkedHashSet()) { it.lowercase() }
        ids.remove(peerId.lowercase())
        ids.add(peerId.lowercase())
        while (ids.size > MAX_TRUSTED_PEERS) {
            ids.remove(ids.first())
        }
        return prefs.edit().putStringSet(KEY_PEER_IDS, ids).commit()
    }

    @Synchronized
    fun forget(context: Context, peerId: String): Boolean {
        if (!isValidPeerId(peerId)) return false
        val prefs = preferences(context)
        val ids = prefs.getStringSet(KEY_PEER_IDS, emptySet())
            .orEmpty()
            .toMutableSet()
        if (!ids.remove(peerId.lowercase())) return true
        return prefs.edit().putStringSet(KEY_PEER_IDS, ids).commit()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
