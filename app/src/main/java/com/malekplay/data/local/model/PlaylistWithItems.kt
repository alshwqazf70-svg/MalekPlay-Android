package com.malekplay.data.local.model

import androidx.room.Embedded
import androidx.room.Relation
import com.malekplay.data.local.entity.PlaylistEntity
import com.malekplay.data.local.entity.PlaylistItemEntity

data class PlaylistWithItems(
    @Embedded val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "playlistId",
        entityColumn = "playlistId",
        entity = PlaylistItemEntity::class
    )
    val items: List<PlaylistItemEntity>
) {
    val trackCount: Int get() = items.size
}
