package com.malekplay.domain.model

import androidx.room.Embedded
import androidx.room.Relation
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.entity.PlaybackProgressEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity

/** Room relation model aligned with the current playback_progress schema. */
data class MediaWithDetails(
    @Embedded val media: MediaItemEntity,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val playbackProgress: PlaybackProgressEntity?,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val trackPreference: TrackPreferenceEntity?
) {
    val progress: Float
        get() {
            val duration = media.durationMs
            if (duration <= 0L) return 0f
            return ((playbackProgress?.positionMs ?: 0L).toFloat() / duration)
                .coerceIn(0f, 1f)
        }

    val isInProgress: Boolean
        get() = (playbackProgress?.positionMs ?: 0L) > 1_000L &&
            playbackProgress?.isCompleted != true
}
