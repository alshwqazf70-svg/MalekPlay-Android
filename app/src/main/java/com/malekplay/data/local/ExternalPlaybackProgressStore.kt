package com.malekplay.data.local

import android.content.Context
import com.malekplay.data.local.entity.PlaybackProgressEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores resume points for document-provider and network Uris.
 *
 * These items intentionally do not get inserted into media_items, because that
 * table is a MediaStore cache and its satellite tables have foreign keys to it.
 * The original Uri is not persisted: preference keys use a SHA-256 digest.
 */
@Singleton
class ExternalPlaybackProgressStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "malekplay_external_progress",
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun save(
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        markPlayed: Boolean
    ) {
        val key = keyFor(mediaId)
        val previous = preferences.getString(key, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val safeDuration = durationMs.coerceAtLeast(0L)
        val completed = safeDuration > 0L && positionMs >= safeDuration - 2_000L
        val playCount = (previous?.optInt("playCount", 0) ?: 0) + if (markPlayed) 1 else 0
        val json = JSONObject()
            .put("positionMs", if (completed) 0L else positionMs.coerceAtLeast(0L))
            .put("durationMs", safeDuration)
            .put("playbackSpeed", speed.coerceIn(0.25f, 4f).toDouble())
            .put("isCompleted", completed)
            .put("lastPlayedAtMs", System.currentTimeMillis())
            .put("playCount", playCount)
        check(preferences.edit().putString(key, json.toString()).commit()) {
            "Could not save external playback progress"
        }
        trimOldEntries()
    }

    @Synchronized
    fun get(mediaId: String): PlaybackProgressEntity? {
        val key = keyFor(mediaId)
        val json = preferences.getString(key, null)?.let {
            runCatching { JSONObject(it) }.getOrNull()
        } ?: return null
        return runCatching {
            PlaybackProgressEntity(
                mediaId = mediaId,
                positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
                durationMs = json.optLong("durationMs", 0L).coerceAtLeast(0L),
                playbackSpeed = json.optDouble("playbackSpeed", 1.0).toFloat().coerceIn(0.25f, 4f),
                isCompleted = json.optBoolean("isCompleted", false),
                lastPlayedAtMs = json.optLong("lastPlayedAtMs", 0L).coerceAtLeast(0L),
                playCount = json.optInt("playCount", 0).coerceAtLeast(0)
            )
        }.getOrNull()
    }

    @Synchronized
    fun remove(mediaId: String) {
        preferences.edit().remove(keyFor(mediaId)).apply()
    }

    private fun trimOldEntries() {
        val entries = preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX)) return@mapNotNull null
            val json = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return@mapNotNull key to 0L
            key to json.optLong("lastPlayedAtMs", 0L)
        }
        if (entries.size <= MAX_ENTRIES) return
        val removeCount = entries.size - MAX_ENTRIES
        preferences.edit().apply {
            entries.sortedBy { it.second }.take(removeCount).forEach { remove(it.first) }
        }.apply()
    }

    private fun keyFor(mediaId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(mediaId.toByteArray(Charsets.UTF_8))
        return PREFIX + digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private companion object {
        const val PREFIX = "progress_"
        const val MAX_ENTRIES = 200
    }
}
