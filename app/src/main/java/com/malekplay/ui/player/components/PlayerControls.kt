package com.malekplay.ui.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.malekplay.R
import com.malekplay.core.ui.theme.PlayerChrome
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.core.ui.util.formatSpeed

/** One of the round floating buttons that sit under the player title bar. */
@Composable
fun PlayerRoundButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    label: String? = null,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier.size(42.dp)
            .shadow(if (active) 15.dp else 7.dp, CircleShape,
                ambientColor = PlayerChrome.ProgressGlow, spotColor = PlayerChrome.ProgressGlow)
            .clip(CircleShape)
            .background(if (active) Brush.linearGradient(
                listOf(Color(0xFF7E22CE), PlayerChrome.ProgressActive, Color(0xFFC084FC))
            ) else Brush.verticalGradient(listOf(Color(0xE82A2038), PlayerChrome.Surface)))
            .border(1.dp, if (active) Color.White.copy(.42f) else PlayerChrome.ProgressGlow.copy(.65f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        else Icon(icon, contentDescription = contentDescription, tint = Color.White, modifier = Modifier.size(21.dp))
    }
}

// ------------------------------------------------------------------ top bar

@Composable
fun PlayerTopBar(
    title: String,
    modifier: Modifier = Modifier,
    decoderBadge: String? = null,
    onBack: () -> Unit,
    onAudioTrack: () -> Unit,
    onSubtitles: () -> Unit,
    onMore: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xD9000000), Color.Transparent)))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        PlayerRoundButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back), onBack)
        PlayerRoundButton(Icons.Filled.Audiotrack, stringResource(R.string.player_audio_track), onAudioTrack)
        PlayerRoundButton(Icons.Filled.Subtitles, stringResource(R.string.player_subtitle_track), onSubtitles)
        PlayerRoundButton(Icons.Filled.MoreVert, stringResource(R.string.player_more), onMore)
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.weight(1.8f, fill = false)) {
            Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
            decoderBadge?.let { badge ->
                Text(
                    badge,
                    color = PlayerChrome.ProgressActive,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

// ------------------------------------------------------------- control rail

/**
 * The horizontal strip of round actions under the title.
 * The chevron reveals a second row of less-used actions, matching the
 * expand behaviour of the classic player layout.
 */
@Composable
fun PlayerControlRail(
    speedLabel: String,
    expanded: Boolean,
    onExpandToggle: () -> Unit,
    modifier: Modifier = Modifier,
    onEqualizer: () -> Unit,
    onSpeed: () -> Unit,
    onScreenshot: () -> Unit,
    onRotate: () -> Unit,
    onAspect: () -> Unit,
    onSleep: () -> Unit,
    onSubtitleStyle: () -> Unit,
    onStats: () -> Unit,
    onBookmarks: () -> Unit,
    onPip: () -> Unit,
    onRepeat: () -> Unit,
    repeatActive: Boolean
) {
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerRoundButton(Icons.Filled.GraphicEq, stringResource(R.string.player_equalizer), onEqualizer)
            PlayerRoundButton(
                Icons.Filled.PlayArrow, stringResource(R.string.player_speed), onSpeed, label = speedLabel
            )
            PlayerRoundButton(Icons.Filled.PhotoCamera, stringResource(R.string.player_screenshot), onScreenshot)
            PlayerRoundButton(Icons.Filled.AspectRatio, stringResource(R.string.player_aspect), onAspect)
            PlayerRoundButton(Icons.Filled.ScreenRotation, stringResource(R.string.player_rotate), onRotate)
            PlayerRoundButton(
                icon = if (expanded) Icons.Filled.ChevronLeft else Icons.Filled.ChevronRight,
                contentDescription = stringResource(R.string.player_more),
                onClick = onExpandToggle
            )
        }

        AnimatedVisibility(visible = expanded, enter = fadeIn(), exit = fadeOut()) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PlayerRoundButton(Icons.Filled.Info, stringResource(R.string.player_stats), onStats)
                PlayerRoundButton(Icons.Filled.Bedtime, stringResource(R.string.player_sleep_timer), onSleep)
                PlayerRoundButton(Icons.Filled.TextFields, stringResource(R.string.menu_subtitle_style), onSubtitleStyle)
                PlayerRoundButton(Icons.Filled.Bookmark, stringResource(R.string.player_bookmarks), onBookmarks)
                PlayerRoundButton(Icons.Filled.PictureInPicture, stringResource(R.string.player_pip), onPip)
                PlayerRoundButton(
                    Icons.Filled.Repeat, stringResource(R.string.player_repeat), onRepeat, active = repeatActive
                )
            }
        }
    }
}

// ----------------------------------------------------------------- seek bar

@Composable
fun PlayerSeekRow(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    modifier: Modifier = Modifier,
    onScrubFinished: (Long) -> Unit
) {
    val total = durationMs.takeIf { it > 0L } ?: 0L
    var scrubbing by remember { mutableStateOf(false) }
    var scrubValue by remember { mutableFloatStateOf(0f) }
    // While dragging, show the finger position instead of the player position so
    // the bar never fights the user's thumb.
    val shown = if (scrubbing) scrubValue else (if (total > 0L) positionMs.toFloat() / total else 0f)

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            formatDuration(if (scrubbing) (scrubValue * total).toLong() else positionMs),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.width(52.dp)
        )
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            // Buffered range drawn behind the scrubber track.
            if (total > 0L) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Transparent)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth((bufferedMs.coerceAtMost(total).toFloat() / total).coerceIn(0f, 1f))
                            .height(3.dp)
                            .background(PlayerChrome.Buffered, RoundedCornerShape(50))
                    )
                }
            }
            Slider(
                value = shown.coerceIn(0f, 1f),
                onValueChange = { value ->
                    scrubbing = true
                    scrubValue = value
                },
                onValueChangeFinished = {
                    scrubbing = false
                    onScrubFinished((scrubValue * total).toLong())
                },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = PlayerChrome.ProgressActive,
                    activeTrackColor = PlayerChrome.ProgressActive,
                    inactiveTrackColor = PlayerChrome.ProgressInactive
                )
            )
        }
        Text(
            formatDuration(total),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.width(52.dp)
        )
    }
}

// ------------------------------------------------------------ main controls

@Composable
fun PlayerMainControls(
    isPlaying: Boolean,
    hasNext: Boolean,
    hasPrevious: Boolean,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    onPip: () -> Unit,
    onLock: () -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xD9231A31), Color(0xE8100D17))))
            .border(1.dp, PlayerChrome.ProgressGlow.copy(.55f), RoundedCornerShape(28.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayerRoundButton(Icons.Filled.Lock, stringResource(R.string.player_lock), onLock)
        Spacer(Modifier.weight(1f))
        PlayerRoundButton(Icons.Filled.SkipPrevious, stringResource(R.string.player_previous), onPrevious, enabled = hasPrevious)
        PlayerRoundButton(
            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) stringResource(R.string.player_pause) else stringResource(R.string.player_play),
            onClick = onPlayPause, modifier = Modifier.size(66.dp), active = true
        )
        PlayerRoundButton(Icons.Filled.SkipNext, stringResource(R.string.player_next), onNext, enabled = hasNext)
        Spacer(Modifier.weight(1f))
        PlayerRoundButton(Icons.Filled.QueueMusic, stringResource(R.string.lib_playlists), onQueue)
        PlayerRoundButton(Icons.Filled.PictureInPicture, stringResource(R.string.player_pip), onPip)
    }
}

/** Centre-screen play/pause/skip cluster shown when the chrome is visible. */
@Composable
fun PlayerCenterControls(
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(34.dp)
    ) {
        PlayerRoundButton(Icons.Filled.Replay, stringResource(R.string.player_previous), onRewind)
        PlayerRoundButton(
            icon = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) stringResource(R.string.player_pause)
            else stringResource(R.string.player_play),
            onClick = onPlayPause,
            modifier = Modifier.size(58.dp),
            active = true
        )
        PlayerRoundButton(Icons.Filled.Replay, stringResource(R.string.player_next), onForward)
    }
}

/** Floating pill used for brightness / volume / seek feedback. */
@Composable
fun GestureIndicator(
    text: String,
    progress: Float? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.shadow(18.dp, RoundedCornerShape(18.dp),
            ambientColor = PlayerChrome.ProgressGlow, spotColor = PlayerChrome.ProgressGlow),
        shape = RoundedCornerShape(18.dp),
        color = PlayerChrome.SurfaceStrong,
        border = androidx.compose.foundation.BorderStroke(1.dp, PlayerChrome.ProgressGlow)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text, color = Color.White, fontSize = 14.sp)
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .width(110.dp)
                        .height(4.dp)
                        .background(PlayerChrome.ProgressInactive, RoundedCornerShape(50))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(PlayerChrome.ProgressActive, RoundedCornerShape(50))
                    )
                }
            }
        }
    }
}

/** Big translucent label shown while double-tap seeking. */
@Composable
fun SeekPulse(
    label: String,
    isForward: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.shadow(18.dp, CircleShape, ambientColor = PlayerChrome.ProgressGlow, spotColor = PlayerChrome.ProgressGlow)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(Color(0xE82B1A3C), Color(0xE8121019))))
            .border(1.dp, PlayerChrome.ProgressGlow, CircleShape)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (!isForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = Color.White)
        }
        Text(label, color = Color.White, fontSize = 14.sp)
        if (isForward) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Color.White)
        }
    }
}
