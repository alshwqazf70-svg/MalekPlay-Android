package com.malekplay

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class LibraryTab { ALL, VIDEOS, AUDIO }
private data class MediaEntry(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAdded: Long,
    val isVideo: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MalekPlayApp() }
    }
}

private val MalekPlayColors = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    secondary = Color(0xFFDAA84A),
    background = Color(0xFFF4F7F5),
    surface = Color.White,
    onSurface = Color(0xFF17211F)
)

private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 33) arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_AUDIO
    ) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

private fun hasMediaPermission(context: Context): Boolean =
    mediaPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

@Composable
private fun MalekPlayApp() {
    val context = LocalContext.current
    var permitted by remember { mutableStateOf(hasMediaPermission(context)) }
    var entries by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var selected by remember { mutableStateOf<MediaEntry?>(null) }
    var tab by remember { mutableStateOf(LibraryTab.ALL) }
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var isFullscreen by remember { mutableStateOf(false) }
    val setFullscreen: (Boolean) -> Unit = { enabled ->
        isFullscreen = enabled
        val activity = context as? ComponentActivity
        activity?.requestedOrientation = if (enabled) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.window?.let { window ->
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            if (enabled) controller.hide(WindowInsetsCompat.Type.systemBars())
            else controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(enabled = isFullscreen) { setFullscreen(false) }

    val permissionRequest = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permitted = hasMediaPermission(context)
        if (permitted) refresh++
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            val mime = context.contentResolver.getType(uri).orEmpty()
            selected = MediaEntry(uri, uri.lastPathSegment?.substringAfterLast('/') ?: "ملف مختار",
                0L, 0L, 0L, mime.startsWith("video"))
        }
    }

    LaunchedEffect(permitted, refresh) {
        if (permitted) {
            loading = true
            entries = loadMedia(context)
            loading = false
        }
    }

    val shown = remember(entries, tab, query) {
        entries.filter {
            (tab == LibraryTab.ALL ||
                (tab == LibraryTab.VIDEOS && it.isVideo) ||
                (tab == LibraryTab.AUDIO && !it.isVideo)) &&
                it.name.contains(query.trim(), ignoreCase = true)
        }
    }

    MaterialTheme(colorScheme = MalekPlayColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF176B5B)) {
                        Text("M", Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                            color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { refresh++ }) {
                            Icon(Icons.Default.Refresh, "تحديث المكتبة", tint = Color(0xFF176B5B))
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("MalekPlay", fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                            Text("مشغّل الوسائط", fontSize = 12.sp, color = Color(0xFF718079))
                        }
                    }
                }

                selected?.let { item ->
                    Spacer(Modifier.height(12.dp))
                    Card(shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF142923))) {
                        Column {
                            Player(uri = item.uri, isFullscreen = isFullscreen, onToggleFullscreen = setFullscreen)
                            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { selected = null }) { Text("إغلاق المشغل") }
                                Spacer(Modifier.weight(1f))
                                Text(item.name, color = Color.White, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                            }
                        }
                    }
                }

                if (!isFullscreen) {
                Spacer(Modifier.height(15.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { filePicker.launch(arrayOf("video/*", "audio/*")) }) {
                        Icon(Icons.Default.FolderOpen, null)
                        Spacer(Modifier.width(4.dp))
                        Text("فتح ملف")
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("مكتبة الوسائط", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text(if (permitted) entries.size.toString() + " ملف على جهازك"
                            else "اكتشف ملفات الفيديو والصوت",
                            fontSize = 12.sp, color = Color(0xFF718079))
                    }
                }

                Spacer(Modifier.height(9.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    placeholder = { Text("ابحث عن فيديو أو أغنية...") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryTab.values().forEach { choice ->
                        val label = when (choice) {
                            LibraryTab.ALL -> "الكل"
                            LibraryTab.VIDEOS -> "الفيديوهات"
                            LibraryTab.AUDIO -> "الصوتيات"
                        }
                        Surface(
                            Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).clickable { tab = choice },
                            color = if (tab == choice) Color(0xFF176B5B) else Color.White,
                            shape = RoundedCornerShape(11.dp)
                        ) {
                            Text(label, Modifier.padding(vertical = 11.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                color = if (tab == choice) Color.White else Color(0xFF53615B),
                                fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(9.dp))

                when {
                    !permitted -> Column(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.VideoLibrary, null, tint = Color(0xFF176B5B),
                            modifier = Modifier.size(52.dp))
                        Spacer(Modifier.height(10.dp))
                        Text("اسمح بالوصول إلى الوسائط", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("نحتاج الإذن لعرض ملفات الفيديو والصوت على جهازك.",
                            color = Color(0xFF718079), fontSize = 13.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { permissionRequest.launch(mediaPermissions()) }) {
                            Text("منح الإذن")
                        }
                    }
                    loading -> Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    shown.isEmpty() -> Column(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Default.AudioFile, null, tint = Color(0xFF9AA9A1),
                            modifier = Modifier.size(50.dp))
                        Text(if (query.isBlank()) "لم نعثر على ملفات وسائط" else "لا توجد نتائج مطابقة",
                            fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(if (query.isBlank()) "افتح ملفاً أو حدّث المكتبة." else "جرّب كلمة بحث أخرى.",
                            color = Color(0xFF718079), fontSize = 13.sp)
                        Row {
                            Button(onClick = { filePicker.launch(arrayOf("video/*", "audio/*")) }) {
                                Text("اختيار ملف")
                            }
                            TextButton(onClick = { refresh++ }) { Text("تحديث") }
                        }
                    }
                    else -> LazyColumn(
                        Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(shown, key = { it.uri.toString() }) { entry ->
                            MediaRow(entry, selected?.uri == entry.uri) { selected = entry }
                        }
                    }
                }
                Text("MALEKPLAY  •  مكتبة الوسائط",
                    Modifier.align(Alignment.CenterHorizontally).padding(vertical = 5.dp),
                    color = Color(0xFF9AA69F), fontSize = 10.sp, letterSpacing = 1.5.sp)
                }
            }
        }
    }
}

private suspend fun loadMedia(context: Context): List<MediaEntry> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val output = mutableListOf<MediaEntry>()
    val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DURATION)

    fun scan(collection: Uri, video: Boolean) {
        try {
            resolver.query(collection, columns, null, null,
                MediaStore.MediaColumns.DATE_ADDED + " DESC")?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val durationCol = cursor.getColumnIndex(MediaStore.MediaColumns.DURATION)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    output.add(MediaEntry(
                        Uri.withAppendedPath(collection, id.toString()),
                        cursor.getString(nameCol) ?: "ملف بدون اسم",
                        if (durationCol >= 0) cursor.getLong(durationCol) else 0L,
                        if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                        if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                        video
                    ))
                }
            }
        } catch (_: SecurityException) {
        } catch (_: IllegalArgumentException) {
        }
    }
    scan(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
    scan(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, false)
    output.sortedByDescending { it.dateAdded }
}

@Composable
private fun MediaRow(entry: MediaEntry, selected: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor =
            if (selected) Color(0xFFE5F1EC) else Color.White)) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(entry.name, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                Spacer(Modifier.height(5.dp))
                val kind = if (entry.isVideo) "فيديو" else "صوت"
                Text(kind + "  •  " + durationText(entry.durationMs) + "  •  " +
                    sizeText(entry.sizeBytes), color = Color(0xFF7A8981), fontSize = 11.sp)
            }
            Spacer(Modifier.width(12.dp))
            Thumbnail(entry)
        }
    }
}

@Composable
private fun Thumbnail(entry: MediaEntry) {
    val context = LocalContext.current
    var bitmap by remember(entry.uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(entry.uri) {
        if (entry.isVideo) bitmap = withContext(Dispatchers.IO) {
            try { context.contentResolver.loadThumbnail(entry.uri, Size(180, 110), null) }
            catch (_: Exception) { null }
        }
    }
    Box(Modifier.size(88.dp, 62.dp).clip(RoundedCornerShape(10.dp))
        .background(Color(0xFFE8EFEB)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), entry.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(if (entry.isVideo) Icons.Default.PlayCircle else Icons.Default.MusicNote,
                null, tint = Color(0xFF176B5B), modifier = Modifier.size(30.dp))
        }
    }
}

@Composable
private fun Player(uri: Uri, isFullscreen: Boolean, onToggleFullscreen: (Boolean) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences("malekplay_playback", Context.MODE_PRIVATE)
    }
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
            val savedPosition = preferences.getLong("position:${uri}", 0L)
            prepare()
            if (savedPosition > 5_000L) seekTo(savedPosition)
            playWhenReady = true
        }
    }
    var speedMenu by remember { mutableStateOf(false) }
    var speed by remember(uri) { mutableFloatStateOf(1f) }
    var controlsLocked by remember(uri) { mutableStateOf(false) }
    var subtitleName by remember(uri) { mutableStateOf<String?>(null) }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { subtitleUri ->
        if (subtitleUri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    subtitleUri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { }
            val subtitle = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("ar")
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            val resumeAt = player.currentPosition
            player.setMediaItem(
                MediaItem.Builder().setUri(uri)
                    .setSubtitleConfigurations(listOf(subtitle)).build(),
                resumeAt
            )
            player.prepare()
            player.playWhenReady = true
            subtitleName = subtitleUri.lastPathSegment?.substringAfterLast('/') ?: "تم اختيار الترجمة"
        }
    }

    DisposableEffect(player, uri, context) {
        val activity = context as? ComponentActivity
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player.pause()
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose {
            activity?.lifecycle?.removeObserver(observer)
            preferences.edit().putLong("position:${uri}", player.currentPosition).apply()
            player.release()
        }
    }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AndroidView(
                factory = { PlayerView(it).apply { this.player = player; useController = !controlsLocked } },
                update = { it.player = player; it.useController = !controlsLocked },
                modifier = Modifier.fillMaxSize()
            )
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
        if (!controlsLocked) Row(
            Modifier.fillMaxWidth().background(Color(0xFF142923)).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TextButton(onClick = { player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L)) }) {
                Text("−10 ث", color = Color.White)
            }
            TextButton(onClick = { player.seekTo(player.currentPosition + 10_000L) }) {
                Text("+10 ث", color = Color.White)
            }
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
            TextButton(onClick = { onToggleFullscreen(!isFullscreen) }) {
                Text(if (isFullscreen) "تصغير" else "ملء الشاشة", color = Color.White)
            }
        }
    }
}

private fun durationText(ms: Long): String {
    if (ms <= 0L) return "--:--"
    val seconds = ms / 1000
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainder = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainder)
    else "%d:%02d".format(minutes, remainder)
}

private fun sizeText(bytes: Long): String {
    if (bytes <= 0L) return "الحجم غير معروف"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024.0) "%.1f GB".format(mb / 1024.0) else "%.1f MB".format(mb)
}
