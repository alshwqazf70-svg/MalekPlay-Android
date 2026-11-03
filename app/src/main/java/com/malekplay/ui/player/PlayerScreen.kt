package com.malekplay.ui.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.net.Uri
import android.os.Build
import android.util.TypedValue
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.malekplay.translation.SubtitleTranslationEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@UnstableApi
@Composable
fun PlayerScreen(
    uri: Uri,
    isVideo: Boolean,
    title: String,
    queueUris: List<Uri>,
    startIndex: Int,
    isFullscreen: Boolean,
    onToggleFullscreen: (Boolean) -> Unit,
    resumePlayback: Boolean = true,
    defaultPlaybackSpeed: Float = 1f,
    rememberBrightness: Boolean = true,
    subtitleSizeSp: Int = 20
) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("malekplay_playback", Context.MODE_PRIVATE)
    }
    val playbackQueue = remember(uri, queueUris) {
        if (queueUris.isEmpty()) listOf(uri) else queueUris
    }
    val safeStartIndex = startIndex.coerceIn(0, playbackQueue.lastIndex)

    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playerController by playerViewModel.controller.collectAsState()
    val connectionError by playerViewModel.connectionError.collectAsState()

    LaunchedEffect(playerViewModel) {
        playerViewModel.connect()
    }
    val player = playerController

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        preferences.edit().putBoolean("notification_permission_requested", true).apply()
    }

    LaunchedEffect(player) {
        if (player != null &&
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !preferences.getBoolean("notification_permission_requested", false)
        ) {
            preferences.edit().putBoolean("notification_permission_requested", true).apply()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(player, uri, playbackQueue, safeStartIndex, resumePlayback, defaultPlaybackSpeed, title) {
        if (player != null) {
            val startUri = playbackQueue[safeStartIndex]
            val roomState = if (resumePlayback) playerViewModel.getPlaybackProgress(startUri.toString()) else null
            val savedPosition = if (resumePlayback) {
                roomState?.positionMs?.coerceAtLeast(0L) ?: preferences.getLong("position:$startUri", 0L).coerceAtLeast(0L)
            } else 0L
            val savedSpeed = roomState?.playbackSpeed ?: preferences.getFloat("speed:$startUri", defaultPlaybackSpeed).coerceIn(0.25f, 4f)
            
            playerViewModel.playUriQueue(
                uris = playbackQueue,
                startIndex = safeStartIndex,
                startPositionMs = savedPosition,
                speed = savedSpeed,
                title = title
            )
        }
    }

    if (player == null) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!connectionError) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (connectionError) "تعذر الاتصال بخدمة المشغل" else "جارٍ تهيئة المشغل الخارق...",
                    color = Color.White,
                    fontSize = 15.sp
                )
                if (connectionError) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { playerViewModel.connect() }) {
                        Text("إعادة المحاولة")
                    }
                }
            }
        }
        return
    }

    // --- حالات المشغل والواجهة ---
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var currentIsVideo by remember(player, uri, isVideo) { mutableStateOf(isVideoMedia(context, uri, isVideo)) }
    var currentTitle by remember(player, uri, title) { mutableStateOf(title) }
    var controlsLocked by rememberSaveable { mutableStateOf(false) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var currentPosition by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var isDraggingSeek by remember { mutableStateOf(false) }
    var dragSeekPosition by remember { mutableLongStateOf(0L) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // الترجمة
    var externalSubtitleUri by remember { mutableStateOf<Uri?>(null) }
    var translatingSubtitle by remember { mutableStateOf(false) }
    var translationMessage by remember { mutableStateOf<String?>(null) }
    val translationScope = rememberCoroutineScope()

    // الإيماءات والمؤشرات
    var gestureIndicator by remember { mutableStateOf<String?>(null) }
    var gestureIcon by remember { mutableStateOf<androidx.compose.ui.graphics.vector.ImageVector?>(null) }

    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // حلقة تحديث الوقت المنقضي
    LaunchedEffect(player, isPlaying) {
        while (true) {
            if (!isDraggingSeek) {
                currentPosition = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.coerceAtLeast(0L)
            }
            delay(500)
        }
    }

    // إخفاء الأزرار تلقائياً بعد 4 ثوانٍ
    LaunchedEffect(areControlsVisible, isPlaying) {
        if (areControlsVisible && isPlaying && !controlsLocked) {
            delay(4000)
            areControlsVisible = false
        }
    }

    // مراقبة أحداث المشغل
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                val nextUri = mediaItem?.localConfiguration?.uri ?: return
                currentIsVideo = isVideoMedia(context, nextUri, currentIsVideo)
                currentTitle = mediaItem.mediaMetadata.title?.toString()
                    ?.takeIf { it.isNotBlank() }
                    ?: Uri.decode(nextUri.lastPathSegment.orEmpty()).substringAfterLast('/').ifBlank { title }
                externalSubtitleUri = null
                translatingSubtitle = false
                translationMessage = null
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // المعادل الصوتي
    var showEqualizer by remember { mutableStateOf(false) }
    var equalizer by remember { mutableStateOf<Equalizer?>(null) }
    var equalizerBands by remember { mutableStateOf<List<Float>>(emptyList()) }
    var equalizerRange by remember { mutableStateOf(shortArrayOf(-1500, 1500)) }
    var audioSessionId by remember { mutableIntStateOf(0) }

    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(id: Int) { audioSessionId = id }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(audioSessionId) {
        equalizer?.release()
        equalizer = if (audioSessionId > 0) runCatching { Equalizer(0, audioSessionId).apply { enabled = true } }.getOrNull() else null
        equalizer?.let { eq ->
            equalizerRange = eq.bandLevelRange
            equalizerBands = (0 until eq.numberOfBands.toInt()).map { eq.getBandLevel(it.toShort()).toFloat() }
        }
        onDispose { equalizer?.release() }
    }

    // السرعة ومؤقت النوم
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    val sleepTimerActive by playerViewModel.sleepTimerActive.collectAsState()
    var speed by remember { mutableFloatStateOf(defaultPlaybackSpeed) }

    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { subUri ->
        if (subUri != null) {
            externalSubtitleUri = subUri
            val subtitleConfig = androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(subUri)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("ar")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            val currentItem = player.currentMediaItem ?: return@rememberLauncherForActivityResult
            player.replaceMediaItem(player.currentMediaItemIndex, currentItem.buildUpon().setSubtitleConfigurations(listOf(subtitleConfig)).build())
            player.seekTo(player.currentPosition)
            translationMessage = "تم تحميل الترجمة"
        }
    }

    // واجهة الشاشة
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!controlsLocked) {
                    areControlsVisible = !areControlsVisible
                }
            }
    ) {
        if (currentIsVideo) {
            // عرض الفيديو الأساسي
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        this.resizeMode = resizeMode
                        this.useController = false // نستخدم واجهتنا الخرافية بدلاً من الافتراضية
                        this.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSizeSp.toFloat())
                    }
                },
                update = { view ->
                    view.player = player
                    view.resizeMode = resizeMode
                    view.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSizeSp.toFloat())
                },
                modifier = Modifier.fillMaxSize()
            )

            // --- نظام إيماءات اللمس الشامل (MX Gestures Zones) ---
            if (!controlsLocked) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            var dragDirection = 0 // 1: عمودي يسار (سطوع), 2: عمودي يمين (صوت), 3: أفقي (تقديم)
                            var totalDragX = 0f
                            var totalDragY = 0f

                            detectDragGestures(
                                onDragStart = { offset ->
                                    totalDragX = 0f
                                    totalDragY = 0f
                                    dragDirection = if (offset.x < size.width * 0.4f) 1 else if (offset.x > size.width * 0.6f) 2 else 3
                                },
                                onDragEnd = {
                                    gestureIndicator = null
                                    gestureIcon = null
                                    dragDirection = 0
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    totalDragX += dragAmount.x
                                    totalDragY += dragAmount.y

                                    when (dragDirection) {
                                        1 -> { // السطوع (يسار الشاشة)
                                            val window = (context as? Activity)?.window
                                            if (window != null && rememberBrightness) {
                                                val current = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                                                val next = (current - dragAmount.y / 800f).coerceIn(0.05f, 1f)
                                                window.attributes = window.attributes.apply { screenBrightness = next }
                                                gestureIndicator = "${(next * 100).toInt()}%"
                                                gestureIcon = Icons.Default.BrightnessMedium
                                            }
                                        }
                                        2 -> { // الصوت (يمين الشاشة)
                                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                            val step = if (dragAmount.y < 0) 1 else -1
                                            if (Math.abs(totalDragY) > 25f) {
                                                val next = (current + step).coerceIn(0, max)
                                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                                                gestureIndicator = "${(next * 100 / max)}%"
                                                gestureIcon = if (next == 0) Icons.Default.VolumeMute else Icons.Default.VolumeUp
                                                totalDragY = 0f
                                            }
                                        }
                                        3 -> { // التقديم والتأخير (منتصف الشاشة)
                                            val seekSeconds = (totalDragX / 15f).toLong()
                                            val target = (player.currentPosition + seekSeconds * 1000L).coerceIn(0L, duration)
                                            player.seekTo(target)
                                            gestureIndicator = "${if (seekSeconds >= 0) "+" else ""}$seekSeconds ث"
                                            gestureIcon = if (seekSeconds >= 0) Icons.Default.FastForward else Icons.Default.FastRewind
                                        }
                                    }
                                }
                            )
                        }
                )
            }
        }

        // --- مؤشر الإيماءات في منتصف الشاشة (Gesture HUD) ---
        gestureIndicator?.let { text ->
            Surface(
                modifier = Modifier.align(Alignment.Center),
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    gestureIcon?.let { icon ->
                        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- عناصر التحكم العلوية والسفلية (Controls Overlay) ---
        AnimatedVisibility(
            visible = areControlsVisible && !controlsLocked,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // 1. الشريط العلوي (Top Controls)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent)))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onToggleFullscreen(false) }) {
                        Icon(Icons.Default.ArrowForward, contentDescription = "رجوع", tint = Color.White)
                    }

                    Text(
                        text = currentTitle,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )

                    // زر المسار الصوتي
                    IconButton(onClick = {
                        TrackSelectionDialogBuilder(context, "المسار الصوتي", player, C.TRACK_TYPE_AUDIO).build().show()
                    }) {
                        Icon(Icons.Default.Audiotrack, contentDescription = "الصوت", tint = Color.White)
                    }

                    // زر الترجمة
                    IconButton(onClick = {
                        TrackSelectionDialogBuilder(context, "الترجمة", player, C.TRACK_TYPE_TEXT).build().show()
                    }) {
                        Icon(Icons.Default.Subtitles, contentDescription = "الترجمة", tint = Color.White)
                    }

                    // زر الذكاء الاصطناعي لترجمة الـ SRT الفورية (الميزة الخارقة)
                    IconButton(onClick = {
                        if (externalSubtitleUri != null && !translatingSubtitle) {
                            translatingSubtitle = true
                            translationScope.launch {
                                try {
                                    val translated = SubtitleTranslationEngine(context).translateSrtToArabic(externalSubtitleUri!!)
                                    val config = androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(translated)
                                        .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("ar").build()
                                    player.replaceMediaItem(player.currentMediaItemIndex, player.currentMediaItem!!.buildUpon().setSubtitleConfigurations(listOf(config)).build())
                                    translationMessage = "تمت الترجمة للعربية بنجاح ✓"
                                } catch (e: Exception) {
                                    translationMessage = "فشلت الترجمة: ${e.localizedMessage}"
                                } finally {
                                    translatingSubtitle = false
                                }
                            }
                        } else {
                            subtitlePicker.launch(arrayOf("application/x-subrip", "text/plain", "text/*"))
                        }
                    }) {
                        Icon(
                            Icons.Default.Translate,
                            contentDescription = "ترجمة الذكاء الاصطناعي",
                            tint = if (translatingSubtitle) MaterialTheme.colorScheme.primary else Color.White
                        )
                    }

                    // زر أبعاد الشاشة (Fit / Zoom / Stretch)
                    IconButton(onClick = {
                        resizeMode = when (resizeMode) {
                            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    }) {
                        Icon(Icons.Default.AspectRatio, contentDescription = "أبعاد الشاشة", tint = Color.White)
                    }

                    // المعادل والسرعة
                    IconButton(onClick = { showEqualizer = true }) {
                        Icon(Icons.Default.Equalizer, contentDescription = "المعادل", tint = Color.White)
                    }
                }

                // 2. الشريط السفلي (Bottom Controls & Seekbar)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    // سطر التوقيت وشريط التقدم
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTime(if (isDraggingSeek) dragSeekPosition else currentPosition),
                            color = Color.White,
                            fontSize = 12.sp
                        )

                        Slider(
                            value = (if (isDraggingSeek) dragSeekPosition else currentPosition).toFloat(),
                            onValueChange = {
                                isDraggingSeek = true
                                dragSeekPosition = it.toLong()
                            },
                            onValueChangeFinished = {
                                player.seekTo(dragSeekPosition)
                                isDraggingSeek = false
                            },
                            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                            )
                        )

                        Text(
                            text = formatTime(duration),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }

                    // أزرار التشغيل والتنقل الرئيسية
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // قفل الشاشة (Lock)
                        IconButton(onClick = {
                            controlsLocked = true
                            areControlsVisible = false
                        }) {
                            Icon(Icons.Default.LockOpen, contentDescription = "قفل", tint = Color.White)
                        }

                        // الأزرار المركزية (سابق، -10، تشغيل/إيقاف، +10، تالي)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { player.seekToPreviousMediaItem() }) {
                                Icon(Icons.Default.SkipPrevious, contentDescription = null, tint = Color.White)
                            }
                            IconButton(onClick = { player.seekTo((player.currentPosition - 10000L).coerceAtLeast(0L)) }) {
                                Icon(Icons.Default.Replay10, contentDescription = null, tint = Color.White)
                            }

                            // زر التشغيل/الإيقاف الكبير
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(54.dp).padding(4.dp)
                            ) {
                                IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                                    Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }

                            IconButton(onClick = { player.seekTo((player.currentPosition + 10000L).coerceAtMost(duration)) }) {
                                Icon(Icons.Default.Forward10, contentDescription = null, tint = Color.White)
                            }
                            IconButton(onClick = { player.seekToNextMediaItem() }) {
                                Icon(Icons.Default.SkipNext, contentDescription = null, tint = Color.White)
                            }
                        }

                        // زر التحكم بالسرعة
                        Box {
                            TextButton(onClick = { speedMenu = true }) {
                                Text("${speed}x", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s ->
                                    DropdownMenuItem(
                                        text = { Text("${s}x") },
                                        onClick = {
                                            speed = s
                                            player.setPlaybackSpeed(s)
                                            speedMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- زر فك القفل العائم (Floating Unlock Button) ---
        if (controlsLocked) {
            IconButton(
                onClick = {
                    controlsLocked = false
                    areControlsVisible = true
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(20.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(Icons.Default.Lock, contentDescription = "فك القفل", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }

    // نافذة المعادل الصوتي
    if (showEqualizer) {
        AlertDialog(
            onDismissRequest = { showEqualizer = false },
            title = { Text("المعادل الصوتي الاحترافي (5-Band)") },
            text = {
                if (equalizer == null) {
                    Text("المعادل الصوتي غير مدعوم على هذا الجهاز.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        equalizerBands.forEachIndexed { index, level ->
                            val freq = runCatching { equalizer?.getCenterFreq(index.toShort())?.div(1000) ?: 0 }.getOrDefault(0)
                            Text("$freq Hz", fontSize = 12.sp)
                            Slider(
                                value = level,
                                onValueChange = { next ->
                                    val updated = equalizerBands.toMutableList()
                                    updated[index] = next
                                    equalizerBands = updated
                                    runCatching { equalizer?.setBandLevel(index.toShort(), next.toInt().toShort()) }
                                },
                                valueRange = equalizerRange[0].toFloat()..equalizerRange[1].toFloat()
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showEqualizer = false }) { Text("تم") } }
        )
    }
}

// دالة تنسيق الوقت (00:00 أو 00:00:00)
fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

private fun isVideoMedia(context: Context, uri: Uri, fallback: Boolean): Boolean {
    val mimeType = runCatching {
        if (uri.scheme.equals("content", ignoreCase = true)) {
            context.contentResolver.getType(uri)
        } else null
    }.getOrNull()

    if (mimeType != null) {
        return mimeType.startsWith("video/", ignoreCase = true)
    }

    val path = uri.lastPathSegment?.lowercase() ?: return fallback
    val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "flv", "wmv")
    val ext = path.substringAfterLast('.', "")
    return if (ext.isNotEmpty()) ext in videoExtensions else fallback
}
