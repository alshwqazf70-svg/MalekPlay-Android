package com.malekplay.network

import android.content.Context
import android.net.Uri
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.ultraShareQueueDataStore by preferencesDataStore(
    name = "ultra_share_queue",
    corruptionHandler = ReplaceFileCorruptionHandler { throw it }
)

data class UltraShareQueueEntry(
    val uri: String,
    val displayName: String,
    val status: String,
    val error: String? = null
) {
    val parsedUri: Uri get() = Uri.parse(uri)
}

object UltraShareQueueStore {
    private val QUEUE = stringPreferencesKey("queue_json")

    fun observe(context: Context): Flow<List<UltraShareQueueEntry>> =
        context.applicationContext.ultraShareQueueDataStore.data.map { decode(it[QUEUE].orEmpty()) }

    suspend fun pending(context: Context): List<UltraShareQueueEntry> =
        observe(context).first().filter { it.status == STATUS_PENDING || it.status == STATUS_FAILED }

    suspend fun replace(context: Context, uris: List<Uri>) {
        val entries = uris.distinctBy { it.toString() }.map { uri ->
            UltraShareQueueEntry(
                uri = uri.toString(),
                displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "ملف",
                status = STATUS_PENDING
            )
        }
        context.applicationContext.ultraShareQueueDataStore.edit {
            it[QUEUE] = encode(entries)
        }
    }

    /**
     * Adds a new transfer batch without deleting earlier history.
     * URIs in the new batch are reset to pending; unrelated history is retained.
     */
    suspend fun enqueue(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        context.applicationContext.ultraShareQueueDataStore.edit { preferences ->
            val existing = decodeForMutation(preferences[QUEUE].orEmpty())
            val incoming = uris.distinctBy { it.toString() }
            val incomingUris = incoming.mapTo(mutableSetOf()) { it.toString() }
            val retained = existing.filterNot { it.uri in incomingUris }
            val added = incoming.map { uri ->
                UltraShareQueueEntry(
                    uri = uri.toString(),
                    displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "ملف",
                    status = STATUS_PENDING
                )
            }
            preferences[QUEUE] = encode(retained + added)
        }
    }

    suspend fun update(
        context: Context,
        uri: Uri,
        displayName: String? = null,
        status: String,
        error: String? = null
    ) {
        context.applicationContext.ultraShareQueueDataStore.edit { preferences ->
            val entries = decodeForMutation(preferences[QUEUE].orEmpty()).map { entry ->
                if (entry.uri == uri.toString()) entry.copy(
                    displayName = displayName ?: entry.displayName,
                    status = status,
                    error = error
                ) else entry
            }
            preferences[QUEUE] = encode(entries)
        }
    }

    suspend fun recoverInterrupted(context: Context) {
        context.applicationContext.ultraShareQueueDataStore.edit { preferences ->
            val entries = decodeForMutation(preferences[QUEUE].orEmpty()).map { entry ->
                if (entry.status == STATUS_SENDING) entry.copy(
                    status = STATUS_PENDING,
                    error = "انقطع التطبيق أثناء النقل؛ يمكنك استئناف الملف."
                ) else entry
            }
            preferences[QUEUE] = encode(entries)
        }
    }

    suspend fun claimForSending(
        context: Context,
        uri: Uri,
        displayName: String
    ): Boolean {
        var claimed = false
        context.applicationContext.ultraShareQueueDataStore.edit { preferences ->
            val entries = decodeForMutation(preferences[QUEUE].orEmpty()).map { entry ->
                if (entry.uri == uri.toString() &&
                    (entry.status == STATUS_PENDING || entry.status == STATUS_FAILED)
                ) {
                    claimed = true
                    entry.copy(
                        displayName = displayName,
                        status = STATUS_SENDING,
                        error = null
                    )
                } else entry
            }
            preferences[QUEUE] = encode(entries)
        }
        return claimed
    }

    suspend fun skip(context: Context, uri: Uri) {
        context.applicationContext.ultraShareQueueDataStore.edit { preferences ->
            val entries = decodeForMutation(preferences[QUEUE].orEmpty()).map { entry ->
                if (entry.uri == uri.toString() &&
                    (entry.status == STATUS_PENDING || entry.status == STATUS_FAILED)
                ) {
                    entry.copy(
                        status = STATUS_SKIPPED,
                        error = "استبعد المستخدم هذا الملف من طابور الاستئناف."
                    )
                } else entry
            }
            preferences[QUEUE] = encode(entries)
        }
    }

    suspend fun clear(context: Context) {
        context.applicationContext.ultraShareQueueDataStore.edit { it.remove(QUEUE) }
    }

    private fun encode(entries: List<UltraShareQueueEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("uri", entry.uri)
                put("name", entry.displayName)
                put("status", entry.status)
                put("error", entry.error ?: JSONObject.NULL)
            })
        }
    }.toString()

    /**
     * A corrupt root is visible as an empty observation, but every mutating operation
     * refuses to replace it. This prevents a later status update from silently erasing
     * the user's recoverable raw queue payload. Malformed individual rows remain isolated.
     */
    internal fun decodeForMutation(raw: String): List<UltraShareQueueEntry> {
        if (raw.isBlank()) return emptyList()
        try {
            JSONArray(raw)
        } catch (failure: Exception) {
            throw IllegalStateException(
                "بيانات طابور النقل تالفة؛ تم إيقاف التعديل للحفاظ على البيانات الأصلية.",
                failure
            )
        }
        return decode(raw)
    }

    /**
     * Decode entries independently: one malformed queue row must not erase every
     * other pending/completed transfer from the user's local history.
     */
    internal fun decode(raw: String): List<UltraShareQueueEntry> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val decoded = LinkedHashMap<String, UltraShareQueueEntry>()
        for (index in 0 until array.length()) {
            val entry = runCatching {
                val item = array.optJSONObject(index) ?: return@runCatching null
                val uri = item.optString("uri").takeIf { it.isNotBlank() } ?: return@runCatching null
                val status = item.optString("status", STATUS_PENDING)
                UltraShareQueueEntry(
                    uri = uri,
                    displayName = item.optString("name", "ملف").takeIf { it.isNotBlank() } ?: "ملف",
                    status = status.takeIf {
                        it in setOf(STATUS_PENDING, STATUS_SENDING, STATUS_COMPLETED, STATUS_FAILED, STATUS_SKIPPED)
                    } ?: STATUS_PENDING,
                    error = item.optString("error").takeIf { it.isNotBlank() && it != "null" }
                )
            }.getOrNull() ?: continue
            if (entry.uri !in decoded) decoded[entry.uri] = entry
        }
        return decoded.values.toList()
    }

    const val STATUS_PENDING = "pending"
    const val STATUS_SENDING = "sending"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_SKIPPED = "skipped"
}
