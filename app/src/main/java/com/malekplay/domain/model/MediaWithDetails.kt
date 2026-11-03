package com.malekplay.domain.model

import androidx.room.Embedded
import androidx.room.Relation
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.entity.PlaybackProgressEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity

/** Room projection retained for callers that need a media row and its saved state. */
data class MediaWithDetails(
    @Embedded val media: MediaItemEntity,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val playbackState: PlaybackProgressEntity?,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val trackPreference: TrackPreferenceEntity?
) {
    val progress: Float
        get() {
            val duration = media.durationMs.takeIf { it > 0L }
                ?: playbackState?.durationMs?.takeIf { it > 0L }
                ?: return 0f
            return ((playbackState?.positionMs ?: 0L).coerceIn(0L, duration).toFloat() /
                duration.toFloat()).coerceIn(0f, 1f)
        }

    val isInProgress: Boolean
        get() = (playbackState?.positionMs ?: 0L) > 0L &&
            playbackState?.isCompleted != true
}
