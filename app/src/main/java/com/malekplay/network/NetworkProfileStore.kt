package com.malekplay.network

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/**
 * Persists the *non-secret* part of a network source.
 * Passwords live only in [NetworkCredentialVault], never in this JSON.
 */
@Singleton
class NetworkProfileStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.applicationContext
        .getSharedPreferences("malekplay_network_profiles", Context.MODE_PRIVATE)

    fun load(): List<NetworkProfile> {
        val raw = prefs.getString("profiles", "[]") ?: "[]"
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val protocol = runCatching {
                    NetworkProtocol.valueOf(item.optString("protocol", "WEBDAV"))
                }.getOrNull() ?: continue
                val id = item.optString("id")
                if (id.isBlank()) continue
                add(
                    NetworkProfile(
                        id = id,
                        protocol = protocol,
                        host = item.optString("host"),
                        port = item.optInt("port", defaultPort(protocol)),
                        username = item.optString("username"),
                        rootPath = item.optString("rootPath", "/"),
                        displayName = item.optString("displayName").ifBlank { item.optString("host") }
                    )
                )
            }
        }
    }

    fun save(profile: NetworkProfile) {
        val updated = load().filterNot { it.id == profile.id } + profile
        persist(updated)
    }

    fun remove(id: String) {
        persist(load().filterNot { it.id == id })
    }

    private fun persist(profiles: List<NetworkProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("protocol", profile.protocol.name)
                    .put("host", profile.host)
                    .put("port", profile.port)
                    .put("username", profile.username)
                    .put("rootPath", profile.rootPath)
                    .put("displayName", profile.displayName)
            )
        }
        prefs.edit().putString("profiles", array.toString()).apply()
    }

    fun defaultPort(protocol: NetworkProtocol): Int = when (protocol) {
        NetworkProtocol.WEBDAV -> 443
        NetworkProtocol.SMB -> 445
        NetworkProtocol.SFTP -> 22
    }
}
