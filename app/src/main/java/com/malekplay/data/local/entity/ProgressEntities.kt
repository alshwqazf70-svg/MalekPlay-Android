package com.malekplay.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Resume point, per-file speed and completion flag. */
@Entity(
    tableName = "playback_progress",
    indices = [Index(value = ["lastPlayedAtMs"])]
)
data class PlaybackProgressEntity(
    @PrimaryKey val mediaId: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1f,
    val isCompleted: Boolean = false,
    val lastPlayedAtMs: Long = 0L,
    val playCount: Int = 0
)

/** Remembered audio/subtitle selection and sync offsets for one file. */
@Entity(
    tableName = "track_preferences",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class,
        parentColumns = ["mediaId"],
        childColumns = ["mediaId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["mediaId"])]
)
data class TrackPreferenceEntity(
    @PrimaryKey val mediaId: String,
    val audioTrackId: String? = null,
    val subtitleTrackId: String? = null,
    val subtitleDelayMs: Long = 0L,
    val audioDelayMs: Long = 0L,
    val isSubtitleEnabled: Boolean = true,
    val externalSubtitleUri: String? = null
)

/** User-tagged moments inside a file. */
@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class,
        parentColumns = ["mediaId"],
        childColumns = ["mediaId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["mediaId"])]
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val bookmarkId: Long = 0L,
    val mediaId: String,
    val positionMs: Long,
    val label: String = "",
    val createdAtMs: Long = System.currentTimeMillis()
)
