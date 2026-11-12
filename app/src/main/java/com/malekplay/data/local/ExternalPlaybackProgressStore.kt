package com.malekplay.data.local

import android.content.Context
import com.malekplay.data.local.entity.PlaybackProgressEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores resume points for document-provider and network Uris without persisting
 * the original Uri. Preference keys are SHA-256 digests of media identifiers.
 */
@Singleton
class ExternalPlaybackProgressStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "malekplay_external_progress", Context.MODE_PRIVATE
    )

    @Synchronized
    fun save(
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        speed: Float,
        markPlayed: Boolean
    ) {
        require(mediaId.isNotBlank()) { "Media id must not be blank" }
        require(positionMs >= 0L) { "Playback position must not be negative" }
        require(durationMs >= 0L) { "Media duration must not be negative" }

        val previous = get(mediaId)
        val safeSpeed = speed.takeIf { it.isFinite() }?.coerceIn(0.25f, 4f) ?: 1f
        val completed = isPlaybackCompleted(positionMs, durationMs)
        val playCount = nextPlaybackCount(previous?.playCount ?: 0, markPlayed)
        val safePosition = if (completed) 0L else
            positionMs.coerceAtMost(durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
        val json = JSONObject()
            .put("positionMs", safePosition)
            .put("durationMs", durationMs)
            .put("playbackSpeed", safeSpeed.toDouble())
            .put("isCompleted", completed)
            .put("lastPlayedAtMs", System.currentTimeMillis())
            .put("playCount", playCount)
        check(preferences.edit().putString(keyFor(mediaId), json.toString()).commit()) {
            "Could not save external playback progress"
        }
        trimOldEntries()
    }

    /** Corrupt records are reported, never silently converted into a missing resume point. */
    @Synchronized
    fun get(mediaId: String): PlaybackProgressEntity? {
        require(mediaId.isNotBlank()) { "Media id must not be blank" }
        val raw = preferences.getString(keyFor(mediaId), null) ?: return null
        return try {
            val json = JSONObject(raw)
            val position = json.getLong("positionMs")
            val duration = json.getLong("durationMs")
            val speed = json.getDouble("playbackSpeed").toFloat()
            val lastPlayed = json.getLong("lastPlayedAtMs")
            val playCount = json.getInt("playCount")
            check(position >= 0L && duration >= 0L && lastPlayed >= 0L && playCount >= 0) {
                "Stored playback progress contains negative values"
            }
            check(speed.isFinite() && speed in 0.25f..4f) {
                "Stored playback speed is invalid"
            }
            PlaybackProgressEntity(
                mediaId = mediaId,
                positionMs = position,
                durationMs = duration,
                playbackSpeed = speed,
                isCompleted = json.getBoolean("isCompleted"),
                lastPlayedAtMs = lastPlayed,
                playCount = playCount
            )
        } catch (error: Exception) {
            throw IllegalStateException("Stored playback progress is corrupted", error)
        }
    }

    @Synchronized
    fun remove(mediaId: String) {
        require(mediaId.isNotBlank()) { "Media id must not be blank" }
        check(preferences.edit().remove(keyFor(mediaId)).commit()) {
            "Could not remove external playback progress"
        }
    }

    private fun trimOldEntries() {
        val entries = preferences.all.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX)) return@mapNotNull null
            val json = (value as? String)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return@mapNotNull key to 0L
            key to json.optLong("lastPlayedAtMs", 0L).coerceAtLeast(0L)
        }
        if (entries.size <= MAX_ENTRIES) return
        val editor = preferences.edit()
        entries.sortedBy { it.second }.take(entries.size - MAX_ENTRIES)
            .forEach { editor.remove(it.first) }
        check(editor.commit()) { "Could not prune old playback progress" }
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
