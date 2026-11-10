package com.malekplay.network

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

private val Context.ultraShareReceivedHistoryDataStore by preferencesDataStore(
    name = "ultra_share_received_history",
    corruptionHandler = ReplaceFileCorruptionHandler { throw it }
)

data class UltraShareReceivedEntry(
    val id: String,
    val filePath: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val receivedAtEpochMs: Long,
    val publishedUri: String? = null
) {
    val file: File get() = File(filePath)
    val isAvailable: Boolean get() = file.isFile && file.length() == sizeBytes
}

object UltraShareReceivedHistoryStore {
    private val HISTORY = stringPreferencesKey("received_json")
    const val MAX_ENTRIES = 100

    fun observe(context: Context): Flow<List<UltraShareReceivedEntry>> =
        context.applicationContext.ultraShareReceivedHistoryDataStore.data.map {
            decode(it[HISTORY].orEmpty())
        }

    suspend fun record(
        context: Context,
        file: File,
        fileName: String,
        mimeType: String,
        sizeBytes: Long,
        receivedAtEpochMs: Long = System.currentTimeMillis(),
        publishedUri: String? = null
    ) {
        require(file.isFile && file.length() == sizeBytes) {
            "لا يمكن حفظ سجل ملف مستلم غير مكتمل أو غير موجود."
        }
        val canonicalPath = file.canonicalPath
        require(canonicalPath.isNotBlank())
        val entry = UltraShareReceivedEntry(
            id = UUID.randomUUID().toString(),
            filePath = canonicalPath,
            fileName = fileName,
            mimeType = mimeType.ifBlank { "application/octet-stream" },
            sizeBytes = sizeBytes,
            receivedAtEpochMs = receivedAtEpochMs,
            publishedUri = publishedUri
        )
        context.applicationContext.ultraShareReceivedHistoryDataStore.edit { preferences ->
            val old = decodeForMutation(preferences[HISTORY].orEmpty())
                .filterNot { it.filePath == canonicalPath && it.sizeBytes == sizeBytes }
            preferences[HISTORY] = encode((listOf(entry) + old).take(MAX_ENTRIES))
        }
    }

    suspend fun remove(context: Context, id: String) {
        context.applicationContext.ultraShareReceivedHistoryDataStore.edit { preferences ->
            preferences[HISTORY] = encode(
                decodeForMutation(preferences[HISTORY].orEmpty()).filterNot { it.id == id }
            )
        }
    }

    suspend fun clear(context: Context) {
        context.applicationContext.ultraShareReceivedHistoryDataStore.edit { it.remove(HISTORY) }
    }

    private fun encode(entries: List<UltraShareReceivedEntry>): String = JSONArray().apply {
        entries.forEach { entry ->
            put(JSONObject().apply {
                put("id", entry.id)
                put("path", entry.filePath)
                put("name", entry.fileName)
                put("mime", entry.mimeType)
                put("size", entry.sizeBytes)
                put("receivedAt", entry.receivedAtEpochMs)
                entry.publishedUri?.let { put("publishedUri", it) }
            })
        }
    }.toString()

    /** Refuse mutations when the root payload is malformed so the raw history survives. */
    internal fun decodeForMutation(raw: String): List<UltraShareReceivedEntry> {
        if (raw.isBlank()) return emptyList()
        try {
            JSONArray(raw)
        } catch (failure: Exception) {
            throw IllegalStateException(
                "سجل الملفات المستلمة تالف؛ تم إيقاف التعديل للحفاظ على البيانات الأصلية.",
                failure
            )
        }
        return decode(raw)
    }

    /** Decode rows independently so one damaged history item cannot hide the others. */
    internal fun decode(raw: String): List<UltraShareReceivedEntry> {
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val entries = LinkedHashMap<String, UltraShareReceivedEntry>()
        for (index in 0 until array.length()) {
            val entry = runCatching {
                val item = array.optJSONObject(index) ?: return@runCatching null
                val path = item.optString("path").takeIf { it.isNotBlank() } ?: return@runCatching null
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: return@runCatching null
                val size = item.optLong("size", -1L)
                val receivedAt = item.optLong("receivedAt", 0L)
                if (size < 0L || receivedAt <= 0L) return@runCatching null
                UltraShareReceivedEntry(
                    id = item.optString("id").ifBlank { "$path:$receivedAt" },
                    filePath = path,
                    fileName = name,
                    mimeType = item.optString("mime").ifBlank { "application/octet-stream" },
                    sizeBytes = size,
                    receivedAtEpochMs = receivedAt,
                    publishedUri = item.optString("publishedUri")
                        .takeIf { it.isNotBlank() && it != "null" }
                )
            }.getOrNull() ?: continue
            if (entry.id !in entries) entries[entry.id] = entry
        }
        return entries.values.take(MAX_ENTRIES)
    }
}
