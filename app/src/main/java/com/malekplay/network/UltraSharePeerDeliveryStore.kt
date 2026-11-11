package com.malekplay.network

import android.content.Context
import android.net.Uri
import androidx.datastore.core.CorruptionException
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.ultraShareDeliveryDataStore by preferencesDataStore(name = "ultra_share_peer_deliveries")

/** Durable state for one file delivered to one approved peer session. No credentials are stored. */
data class UltraSharePeerDeliveryEntry(
    val fileUri: String,
    val peerSessionId: String,
    val peerHost: String,
    val displayName: String,
    val status: String,
    val error: String? = null,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {
    val parsedUri: Uri get() = Uri.parse(fileUri)
}

object UltraSharePeerDeliveryStore {
    private val DELIVERIES = stringPreferencesKey("deliveries_json")

    fun observe(context: Context): Flow<List<UltraSharePeerDeliveryEntry>> =
        context.applicationContext.ultraShareDeliveryDataStore.data
            .catch { failure ->
                if (failure is CorruptionException) emit(emptyPreferences()) else throw failure
            }
            .map { decode(it[DELIVERIES].orEmpty()) }

    suspend fun prepare(
        context: Context,
        files: List<Pair<Uri, String>>,
        peers: List<UltraShareMultiRecipientCoordinator.Target>
    ) {
        require(peers.map { it.sessionId }.distinct().size == peers.size) { "Duplicate peer session IDs." }
        require(peers.map { it.host }.distinct().size == peers.size) { "Duplicate peer hosts." }
        if (files.isEmpty() || peers.isEmpty()) return
        context.applicationContext.ultraShareDeliveryDataStore.edit { preferences ->
            val existing = decodeForMutation(preferences[DELIVERIES].orEmpty())
            val incoming = files.distinctBy { it.first.toString() }.flatMap { (uri, name) ->
                peers.map { peer ->
                    UltraSharePeerDeliveryEntry(
                        fileUri = uri.toString(),
                        peerSessionId = peer.sessionId,
                        peerHost = peer.host,
                        displayName = name.take(240).ifBlank { "ملف" },
                        status = STATUS_PENDING
                    )
                }
            }
            val keys = incoming.mapTo(mutableSetOf()) { key(it.fileUri, it.peerSessionId) }
            preferences[DELIVERIES] = encode(existing.filterNot { key(it.fileUri, it.peerSessionId) in keys } + incoming)
        }
    }

    suspend fun update(
        context: Context,
        fileUri: Uri,
        peerSessionId: String,
        status: String,
        error: String? = null
    ) {
        require(status in STATUSES) { "Unknown peer delivery status." }
        context.applicationContext.ultraShareDeliveryDataStore.edit { preferences ->
            val entries = decodeForMutation(preferences[DELIVERIES].orEmpty()).map { entry ->
                if (entry.fileUri == fileUri.toString() && entry.peerSessionId == peerSessionId) {
                    entry.copy(
                        status = status,
                        error = error?.take(240),
                        updatedAtMillis = System.currentTimeMillis()
                    )
                } else entry
            }
            preferences[DELIVERIES] = encode(entries)
        }
    }

    suspend fun pending(context: Context, peerSessionId: String): List<UltraSharePeerDeliveryEntry> =
        observe(context).first().filter {
            it.peerSessionId == peerSessionId &&
                (it.status == STATUS_PENDING || it.status == STATUS_FAILED)
        }

    suspend fun recoverInterrupted(context: Context) {
        context.applicationContext.ultraShareDeliveryDataStore.edit { preferences ->
            val recovered = decodeForMutation(preferences[DELIVERIES].orEmpty()).map { entry ->
                if (entry.status == STATUS_SENDING) entry.copy(
                    status = STATUS_PENDING,
                    error = "انقطع التطبيق أثناء الإرسال إلى هذا الجهاز؛ يمكن استئناف هذه الوجهة."
                ) else entry
            }
            preferences[DELIVERIES] = encode(recovered)
        }
    }

    suspend fun clear(context: Context) {
        context.applicationContext.ultraShareDeliveryDataStore.edit { it.remove(DELIVERIES) }
    }

    internal fun decodeForMutation(raw: String): List<UltraSharePeerDeliveryEntry> {
        if (raw.isBlank()) return emptyList()
        try {
            JSONArray(raw)
        } catch (failure: Exception) {
            throw IllegalStateException("بيانات سجل الإرسال للأجهزة تالفة؛ أوقفنا التعديل لحمايتها.", failure)
        }
        return decode(raw)
    }

    internal fun decode(raw: String): List<UltraSharePeerDeliveryEntry> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val decoded = LinkedHashMap<String, UltraSharePeerDeliveryEntry>()
        for (index in 0 until array.length()) {
            val entry = runCatching {
                val item = array.optJSONObject(index) ?: return@runCatching null
                val uri = item.optString("fileUri").takeIf { it.isNotBlank() } ?: return@runCatching null
                val session = item.optString("peerSessionId").takeIf { it.isNotBlank() } ?: return@runCatching null
                val host = item.optString("peerHost").takeIf { it.isNotBlank() } ?: return@runCatching null
                val status = item.optString("status", STATUS_PENDING)
                UltraSharePeerDeliveryEntry(
                    fileUri = uri,
                    peerSessionId = session,
                    peerHost = host,
                    displayName = item.optString("displayName", "ملف").takeIf { it.isNotBlank() } ?: "ملف",
                    status = status.takeIf { it in STATUSES } ?: STATUS_PENDING,
                    error = item.optString("error").takeIf { it.isNotBlank() && it != "null" },
                    updatedAtMillis = item.optLong("updatedAtMillis", 0L)
                )
            }.getOrNull() ?: continue
            decoded.putIfAbsent(key(entry.fileUri, entry.peerSessionId), entry)
        }
        return decoded.values.toList()
    }

    private fun encode(entries: List<UltraSharePeerDeliveryEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("fileUri", entry.fileUri)
                put("peerSessionId", entry.peerSessionId)
                put("peerHost", entry.peerHost)
                put("displayName", entry.displayName)
                put("status", entry.status)
                put("error", entry.error ?: JSONObject.NULL)
                put("updatedAtMillis", entry.updatedAtMillis)
            })
        }
    }.toString()

    private fun key(uri: String, sessionId: String): String = "$uri\u0000$sessionId"

    private val STATUSES = setOf(STATUS_PENDING, STATUS_SENDING, STATUS_COMPLETED, STATUS_FAILED)
    const val STATUS_PENDING = "pending"
    const val STATUS_SENDING = "sending"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
}
