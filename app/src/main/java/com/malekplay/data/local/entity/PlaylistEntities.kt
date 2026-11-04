package com.malekplay.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val playlistId: String,
    val name: String,
    val createdAtMs: Long = System.currentTimeMillis(),
    val sortOrder: Int = 0
)

/**
 * Junction row. `position` keeps a stable manual order; lower renders first.
 * The primary key is composite so re-adding the same file is a no-op upsert.
 */
@Entity(
    tableName = "playlist_items",
    primaryKeys = ["playlistId", "mediaId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["playlistId"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["mediaId"]),
        Index(value = ["playlistId", "position"])
    ]
)
data class PlaylistItemEntity(
    val playlistId: String,
    val mediaId: String,
    val position: Int = 0,
    val addedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "favourites", indices = [Index(value = ["addedAtMs"])])
data class FavouriteEntity(
    @PrimaryKey val mediaId: String,
    val addedAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "history", indices = [Index(value = ["lastPlayedAtMs"])])
data class HistoryEntity(
    @PrimaryKey val mediaId: String,
    val lastPlayedAtMs: Long = System.currentTimeMillis(),
    val playCount: Int = 1
)
