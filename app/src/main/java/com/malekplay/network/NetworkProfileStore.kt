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
    private val appContext = context.applicationContext
    private val prefs = appContext
        .getSharedPreferences("malekplay_network_profiles", Context.MODE_PRIVATE)
    private val credentialVault = NetworkCredentialVault(appContext)

    fun load(): List<NetworkProfile> = synchronized(STORE_LOCK) {
        loadLocked()
    }

    private fun loadLocked(): List<NetworkProfile> {
        val raw = prefs.getString("profiles", "[]") ?: "[]"
        val array = try {
            JSONArray(raw)
        } catch (error: Exception) {
            throw IllegalStateException("Saved network profiles are corrupted", error)
        }
        val seenIds = HashSet<String>()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i)
                    ?: throw IllegalStateException("Saved network profile at index $i is not an object")
                try {
                    val protocol = NetworkProtocol.valueOf(item.optString("protocol", "WEBDAV"))
                    val id = item.getString("id")
                    val host = normalizeNetworkHost(item.getString("host"))
                    val profile = NetworkProfile(
                        id = id,
                        protocol = protocol,
                        host = host,
                        port = item.optInt("port", defaultPort(protocol)),
                        username = item.optString("username"),
                        rootPath = item.optString("rootPath", "/"),
                        displayName = item.optString("displayName").ifBlank {
                            item.optString("name").ifBlank { host }
                        }
                    )
                    check(seenIds.add(profile.id)) { "Duplicate network profile id at index $i" }
                    add(profile)
                } catch (error: Exception) {
                    throw IllegalStateException("Saved network profile at index $i is invalid", error)
                }
            }
        }
    }

    fun save(profile: NetworkProfile) {
        synchronized(STORE_LOCK) {
            val updated = loadLocked().filterNot { it.id == profile.id } + profile
            persist(updated)
        }
    }

    fun remove(id: String) {
        synchronized(STORE_LOCK) {
            val current = loadLocked()
            persist(current.filterNot { it.id == id })
            try {
                credentialVault.remove(id)
            } catch (failure: Throwable) {
                runCatching { persist(current) }.onFailure(failure::addSuppressed)
                throw failure
            }
        }
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
        check(prefs.edit().putString("profiles", array.toString()).commit()) {
            "Could not persist network profiles"
        }
    }

    private companion object {
        // Store instances can be created by the service and dependency injection.
        // Serialize read-modify-write operations across all instances in this process.
        val STORE_LOCK = Any()
    }

    fun defaultPort(protocol: NetworkProtocol): Int = when (protocol) {
        NetworkProtocol.WEBDAV -> 443
        NetworkProtocol.SMB -> 445
        NetworkProtocol.SFTP -> 22
    }
}
