package com.malekplay.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val mediaId: String,
    val title: String,
    val duration: Long,
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateAdded: Long,
    val isVideo: Boolean,
    val thumbnailPath: String? = null
)

@Entity(
    tableName = "playback_states",
    foreignKeys = [ForeignKey(
        entity = MediaItemEntity::class,
        parentColumns = ["mediaId"],
        childColumns = ["mediaId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["mediaId"])]
)
data class PlaybackStateEntity(
    @PrimaryKey val mediaId: String,
    val position: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val isCompleted: Boolean = false
)

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
    val isSubtitleEnabled: Boolean = true
)
