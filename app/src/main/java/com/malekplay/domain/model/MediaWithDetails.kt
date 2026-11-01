package com.malekplay.domain.model

import androidx.room.Embedded
import androidx.room.Relation
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.entity.PlaybackStateEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity

data class MediaWithDetails(
    @Embedded val media: MediaItemEntity,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val playbackState: PlaybackStateEntity?,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val trackPreference: TrackPreferenceEntity?
) {
    val progress: Float
        get() = if (media.duration <= 0L) 0f
        else ((playbackState?.position ?: 0L).toFloat() / media.duration).coerceIn(0f, 1f)

    val isInProgress: Boolean
        get() = (playbackState?.position ?: 0L) > 0L && playbackState?.isCompleted != true
}
