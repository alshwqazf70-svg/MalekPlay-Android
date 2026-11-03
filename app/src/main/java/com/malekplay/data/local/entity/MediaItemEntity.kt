package com.malekplay.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per media file discovered through MediaStore.
 *
 * `mediaId` is the stable MediaStore content Uri string, which doubles as the
 * primary key and as the key used by every satellite table.
 */
@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["folderPath"]),
        Index(value = ["isVideo"]),
        Index(value = ["addedAtMs"]),
        Index(value = ["isVideo", "album"]),
        Index(value = ["isVideo", "artist"])
    ]
)
data class MediaItemEntity(
    @PrimaryKey val mediaId: String,
    val title: String,
    val uri: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val addedAtMs: Long,
    val isVideo: Boolean,
    val folderPath: String,
    val folderName: String,
    val artist: String?,
    val album: String?,
    val albumId: Long?,
    val artistId: Long?,
    val genre: String?,
    val width: Int,
    val height: Int,
    val bitrate: Long
)
