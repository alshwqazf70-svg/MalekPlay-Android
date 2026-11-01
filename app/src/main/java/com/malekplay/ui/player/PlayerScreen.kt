package com.malekplay.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.net.Uri
import com.malekplay.translation.SubtitleTranslationEngine
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.util.TypedValue
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.MimeTypes
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder

@OptIn(UnstableApi::class)
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

    LaunchedEffect(player, uri, playbackQueue, safeStartIndex) {
        if (player != null) {
            val startUri = playbackQueue[safeStartIndex]
            val savedPosition = if (resumePlayback) preferences.getLong("position:$startUri", 0L).coerceAtLeast(0L) else 0L
            playerViewModel.playQueue(playbackQueue, safeStartIndex, savedPosition, defaultPlaybackSpeed)
        }
    }

    if (player == null) {
        Column(
            Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!connectionError) CircularProgressIndicator()
            Spacer(Modifier.height(10.dp))
            Text(
                if (connectionError) "تعذر الاتصال بخدمة التشغيل" else "جارٍ تجهيز المشغل...",
                color = Color(0xFF718079)
            )
            if (connectionError) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { playerViewModel.connect() }) {
                    Text("إعادة المحاولة")
                }
            }
        }
        return
    }

    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    var audioSessionId by remember { mutableIntStateOf(0) }
    var showEqualizer by remember { mutableStateOf(false) }
    var equalizer by remember { mutableStateOf<Equalizer?>(null) }
    var equalizerBands by remember { mutableStateOf<List<Float>>(emptyList()) }
    var equalizerRange by remember { mutableStateOf(shortArrayOf(-1500, 1500)) }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onAudioSessionIdChanged(id: Int) {
                audioSessionId = id
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    DisposableEffect(audioSessionId) {
        equalizer?.release()
        equalizer = if (audioSessionId > 0) runCatching {
            Equalizer(0, audioSessionId).apply { enabled = true }
        }.getOrNull() else null
        equalizer?.let { eq ->
            equalizerRange = eq.bandLevelRange
            equalizerBands = (0 until eq.numberOfBands.toInt()).map { band ->
                eq.getBandLevel(band.toShort()).toFloat()
            }
        } ?: run { equalizerBands = emptyList() }
        onDispose {
            equalizer?.release()
            equalizer = null
        }
    }
    var speedMenu by remember { mutableStateOf(false) }
    var sleepMenu by remember { mutableStateOf(false) }
    val sleepTimerActive by playerViewModel.sleepTimerActive.collectAsState()
    var speed by remember { mutableFloatStateOf(defaultPlaybackSpeed) }
    var controlsLocked by remember { mutableStateOf(false) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    var subtitleName by remember { mutableStateOf<String?>(null) }
    var externalSubtitleUri by remember { mutableStateOf<Uri?>(null) }
    var translatingSubtitle by remember { mutableStateOf(false) }
    var translationMessage by remember { mutableStateOf<String?>(null) }
    val translationScope = rememberCoroutineScope()
    val subtitlePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { subtitleUri ->
        if (subtitleUri != null) {
            externalSubtitleUri = subtitleUri
            translationMessage = null
            try {
                context.contentResolver.takePersistableUriPermission(
                    subtitleUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            val subtitle = androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("ar")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            val currentPosition = player.currentPosition
            val currentItem = player.currentMediaItem ?: androidx.media3.common.MediaItem.fromUri(uri)
            player.setMediaItem(
                currentItem.buildUpon()
                    .setSubtitleConfigurations(listOf(subtitle))
                    .build(),
                currentPosition
            )
            player.prepare()
            player.playWhenReady = true
            subtitleName = subtitleUri.lastPathSegment?.substringAfterLast('/') ?: "تم اختيار الترجمة"
        }
    }

    DisposableEffect(player, context, isVideo) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            // Audio continues through the service; video pauses when its UI leaves foreground.
            if (event == Lifecycle.Event.ON_STOP && isVideo) player.pause()
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            val currentUri = player.currentMediaItem?.localConfiguration?.uri ?: uri
            preferences.edit().putLong("position:$currentUri", player.currentPosition).apply()
        }
    }

    Column {
        if (isVideo) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                AndroidView(
                    factory = {
                        PlayerView(it).apply {
                            this.player = player
                            useController = !controlsLocked
                            subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSizeSp.coerceIn(12, 48).toFloat())
                        }
                    },
                    update = {
                        it.player = player
                        it.useController = !controlsLocked
                        it.subtitleView?.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, subtitleSizeSp.coerceIn(12, 48).toFloat())
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (!controlsLocked) {
                    Box(
                        Modifier.align(Alignment.CenterStart).width(58.dp).fillMaxHeight()
                            .pointerInput(context) {
                                detectVerticalDragGestures { change, dragAmount ->
                                    change.consume()
                                    val window = (context as? Activity)?.window
                                    if (window != null && rememberBrightness) {
                                        val current = window.attributes.screenBrightness
                                            .takeIf { it >= 0f } ?: 0.5f
                                        val next = (current - dragAmount / 900f).coerceIn(0.05f, 1f)
                                        window.attributes = window.attributes.apply {
                                            screenBrightness = next
                                        }
                                        gestureHint = "سطوع الشاشة " + (next * 100).toInt() + "٪"
                                    }
                                }
                            }
                    )
                    Box(
                        Modifier.align(Alignment.CenterEnd).width(58.dp).fillMaxHeight()
                            .pointerInput(audioManager) {
                                var accumulated = 0f
                                detectVerticalDragGestures { change, dragAmount ->
                                    change.consume()
                                    accumulated += dragAmount
                                    if (accumulated <= -12f || accumulated >= 12f) {
                                        val direction = if (accumulated < 0f) 1 else -1
                                        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        val next = (current + direction).coerceIn(0, maximum)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0)
                                        gestureHint = "مستوى الصوت " + (next * 100 / maximum.coerceAtLeast(1)) + "٪"
                                        accumulated = 0f
                                    }
                                }
                            }
                    )
                }
                if (!controlsLocked) {
                    Box(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().height(34.dp)
                            .pointerInput(player) {
                                var accumulated = 0f
                                detectHorizontalDragGestures { change, dragAmount ->
                                    change.consume()
                                    accumulated += dragAmount
                                    if (accumulated >= 28f || accumulated <= -28f) {
                                        val seconds = if (accumulated > 0f) -5L else 5L
                                        val target = (player.currentPosition + seconds * 1000L)
                                            .coerceIn(0L, player.duration.takeIf { it > 0L } ?: Long.MAX_VALUE)
                                        player.seekTo(target)
                                        gestureHint = if (seconds > 0) "تقديم 5 ثوانٍ" else "رجوع 5 ثوانٍ"
                                        accumulated = 0f
                                    }
                                }
                            }
                    )
                }
                gestureHint?.let { hint ->
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 52.dp),
                        color = Color(0xCC142923),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Text(hint, color = Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
                IconButton(
                    onClick = { controlsLocked = !controlsLocked },
                    modifier = Modifier.align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(Color(0x99000000), RoundedCornerShape(50))
                ) {
                    Icon(
                        if (controlsLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                        contentDescription = if (controlsLocked) "فتح عناصر التحكم" else "قفل عناصر التحكم",
                        tint = Color.White
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF142923))
                    .padding(vertical = 22.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(112.dp).clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF245447)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.MusicNote, null, tint = Color(0xFFE4C27B),
                        modifier = Modifier.size(58.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(title, color = Color.White, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                IconButton(onClick = { if (player.isPlaying) player.pause() else player.play() }) {
                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                        tint = Color.White, modifier = Modifier.size(38.dp))
                }
            }
        }
        if (!controlsLocked) Row(
            Modifier.fillMaxWidth().background(Color(0xFF142923)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = {
                player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
            }) { Text("−10 ث", color = Color.White) }
            TextButton(onClick = {
                player.seekTo(player.currentPosition + 10_000L)
            }) { Text("+10 ث", color = Color.White) }
            Box {
                TextButton(onClick = { speedMenu = true }) {
                    Text("${speed}×", color = Color.White)
                }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { value ->
                        DropdownMenuItem(
                            text = { Text("${value}×") },
                            onClick = {
                                speed = value
                                player.setPlaybackSpeed(value)
                                speedMenu = false
                            }
                        )
                    }
                }
            }
            Box {
                TextButton(onClick = { sleepMenu = true }) {
                    Text(if (sleepTimerActive) "المؤقت ✓" else "مؤقت النوم", color = Color.White)
                }
                DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                    listOf(15, 30, 45, 60, 90, 120).forEach { minutes ->
                        DropdownMenuItem(
                            text = { Text("إيقاف بعد $minutes دقيقة") },
                            onClick = {
                                playerViewModel.setSleepTimer(minutes)
                                sleepMenu = false
                            }
                        )
                    }
                    if (sleepTimerActive) {
                        DropdownMenuItem(
                            text = { Text("إلغاء المؤقت") },
                            onClick = {
                                playerViewModel.cancelSleepTimer()
                                sleepMenu = false
                            }
                        )
                    }
                }
            }
            TextButton(onClick = { showEqualizer = true }) {
                Text("المعادل", color = Color.White)
            }
            TextButton(onClick = {
                TrackSelectionDialogBuilder(context, "المسار الصوتي", player, C.TRACK_TYPE_AUDIO)
                    .build().show()
            }) { Text("الصوت", color = Color.White) }
            TextButton(onClick = {
                TrackSelectionDialogBuilder(context, "الترجمة", player, C.TRACK_TYPE_TEXT)
                    .build().show()
            }) { Text("الترجمة", color = Color.White) }
            TextButton(onClick = {
                subtitlePicker.launch(arrayOf("application/x-subrip", "text/plain", "text/*"))
            }) { Text("ملف SRT", color = Color.White) }
            if (externalSubtitleUri != null) {
                TextButton(enabled = !translatingSubtitle, onClick = {
                    val source = externalSubtitleUri
                    if (source != null) {
                        translatingSubtitle = true
                        translationMessage = "جارٍ تجهيز نموذج الترجمة..."
                        translationScope.launch {
                            try {
                                val translatedUri = SubtitleTranslationEngine(context).translateSrtToArabic(source)
                                val currentPosition = player.currentPosition
                                val currentItem = player.currentMediaItem ?: androidx.media3.common.MediaItem.fromUri(uri)
                                val config = androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(translatedUri)
                                    .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                                    .setLanguage("ar")
                                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                    .build()
                                player.setMediaItem(currentItem.buildUpon().setSubtitleConfigurations(listOf(config)).build(), currentPosition)
                                player.prepare()
                                player.playWhenReady = true
                                subtitleName = "ترجمة آلية إلى العربية"
                                translationMessage = "اكتملت الترجمة محلياً"
                            } catch (error: Exception) {
                                translationMessage = "تعذرت الترجمة: " + (error.localizedMessage ?: "تحقق من اتصال الإنترنت لتنزيل النموذج")
                            } finally {
                                translatingSubtitle = false
                            }
                        }
                    }
                }) { Text(if (translatingSubtitle) "جارٍ الترجمة..." else "ترجمة للعربية", color = Color.White) }
            }
            TextButton(onClick = { onToggleFullscreen(!isFullscreen) }) {
                Text(if (isFullscreen) "تصغير" else "ملء الشاشة", color = Color.White)
            }
        }
        if (showEqualizer) {
            AlertDialog(
                onDismissRequest = { showEqualizer = false },
                title = { Text("المعادل الصوتي") },
                text = {
                    if (equalizer == null) {
                        Text("المعادل الصوتي غير متاح لهذا المسار أو على هذا الجهاز.")
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("تحكم بخمسة نطاقات ترددية")
                            equalizerBands.forEachIndexed { index, level ->
                                val frequency = runCatching {
                                    equalizer?.getCenterFreq(index.toShort())?.div(1000) ?: 0
                                }.getOrDefault(0)
                                Text("$frequency Hz")
                                Slider(
                                    value = level,
                                    onValueChange = { next ->
                                        val updated = equalizerBands.toMutableList()
                                        updated[index] = next
                                        equalizerBands = updated
                                        runCatching {
                                            equalizer?.setBandLevel(index.toShort(), next.toInt().toShort())
                                        }
                                    },
                                    valueRange = equalizerRange[0].toFloat()..equalizerRange[1].toFloat()
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showEqualizer = false }) { Text("تم") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        equalizer?.let { eq ->
                            for (band in 0 until eq.numberOfBands.toInt()) {
                                runCatching { eq.setBandLevel(band.toShort(), 0) }
                            }
                            equalizerBands = equalizerBands.map { 0f }
                        }
                    }) { Text("إعادة الضبط") }
                }
            )
        }
        translationMessage?.let {
            Text(it, color = Color(0xFF718079), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        subtitleName?.let {
            Text("الترجمة الخارجية: $it", color = Color(0xFF718079),
                fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }
}

