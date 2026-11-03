package com.malekplay.data.local.model

import androidx.room.Embedded
import androidx.room.Relation
import com.malekplay.data.local.entity.MediaItemEntity
import com.malekplay.data.local.entity.PlaybackProgressEntity
import com.malekplay.data.local.entity.TrackPreferenceEntity

/** A media row joined with its saved playback state. */
data class MediaWithState(
    @Embedded val media: MediaItemEntity,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val progress: PlaybackProgressEntity?,
    @Relation(parentColumn = "mediaId", entityColumn = "mediaId")
    val track: TrackPreferenceEntity?
) {
    /** 0..1 fraction of the file already watched/listened to. */
    val progressFraction: Float
        get() {
            val total = media.durationMs.takeIf { it > 0L } ?: progress?.durationMs?.takeIf { it > 0L }
                ?: return 0f
            return (progress?.positionMs ?: 0L).coerceIn(0L, total).toFloat() / total.toFloat()
        }

    val isCompleted: Boolean get() = progress?.isCompleted == true

    /** Started but neither finished nor untouched — used for "Continue watching". */
    val isInProgress: Boolean
        get() = !isCompleted && (progress?.positionMs ?: 0L) > 1_000L
}
