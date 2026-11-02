package com.malekplay

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlinx.coroutines.flow.collect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.malekplay.ui.library.LibraryViewModel
import com.malekplay.ui.player.PlayerViewModel
import com.malekplay.ui.player.PlayerScreen
import com.malekplay.ui.settings.SettingsViewModel
import com.malekplay.ui.settings.SettingsDialog
import com.malekplay.safety.ParentalControlManager
import com.malekplay.ui.network.NetworkSourcesDialog

private enum class LibraryTab { ALL, VIDEOS, AUDIO, FAVORITES, RECENT, FOLDERS, PLAYLISTS }
private data class UserPlaylist(val name: String, val uris: List<String>)
private enum class SortMode { NEWEST, OLDEST, NAME, LONGEST, LARGEST }
private data class MediaEntry(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAdded: Long,
    val isVideo: Boolean,
    val bucketId: String = "0",
    val bucketName: String = "مجلدات أخرى"
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MalekPlayApp() }
    }
}

private val MalekPlayLightColors = lightColorScheme(
    primary = Color(0xFF176B5B),
    onPrimary = Color.White,
    secondary = Color(0xFFDAA84A),
    background = Color(0xFFF4F7F5),
    surface = Color.White,
    onSurface = Color(0xFF17211F)
)

private val MalekPlayDarkColors = darkColorScheme(
    primary = Color(0xFF78C7B2),
    onPrimary = Color(0xFF073B32),
    secondary = Color(0xFFE5BD70),
    background = Color(0xFF101715),
    surface = Color(0xFF19231F),
    onSurface = Color(0xFFE5EEE9)
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
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val playerSettings by settingsViewModel.settings.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var showNetworkSources by remember { mutableStateOf(false) }
    var childModeEnabled by remember(context) { mutableStateOf(ParentalControlManager(context).isEnabled) }
    val libraryRecords by libraryViewModel.media.collectAsState()
    val loading by libraryViewModel.isLoading.collectAsState()
    val libraryLoadError by libraryViewModel.error.collectAsState()
    var permitted by remember { mutableStateOf(hasMediaPermission(context)) }
    var entries by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var selected by remember { mutableStateOf<MediaEntry?>(null) }
    var tab by remember { mutableStateOf(LibraryTab.ALL) }
    var query by remember { mutableStateOf("") }
    val libraryPrefs = remember(context) {
        context.getSharedPreferences("malekplay_library", Context.MODE_PRIVATE)
    }
    var sortMode by remember {
        mutableStateOf(runCatching { SortMode.valueOf(libraryPrefs.getString("sort_mode", "NEWEST") ?: "NEWEST") }.getOrDefault(SortMode.NEWEST))
    }
    var sortMenu by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf(loadPlaylists(libraryPrefsValue(context, "playlists_json", "[]"))) }
    var activePlaylist by remember { mutableStateOf<String?>(null) }
    var activeFolderId by remember { mutableStateOf<String?>(null) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistNameInput by remember { mutableStateOf("") }
    var addToPlaylistUri by remember { mutableStateOf<Uri?>(null) }
    var queueUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var queueStartIndex by remember { mutableIntStateOf(0) }
    fun persistPlaylists(updated: List<UserPlaylist>) {
        playlists = updated
        libraryPrefs.edit().putString("playlists_json", savePlaylists(updated)).apply()
    }
    fun createPlaylist() {
        val name = playlistNameInput.trim()
        if (name.isNotEmpty() && playlists.none { it.name.equals(name, true) }) {
            persistPlaylists(playlists + UserPlaylist(name, emptyList()))
            playlistNameInput = ""
            showCreatePlaylist = false
        }
    }
    fun addMediaToPlaylist(uri: Uri, name: String) {
        persistPlaylists(playlists.map { p ->
            if (p.name == name && uri.toString() !in p.uris) p.copy(uris = p.uris + uri.toString()) else p
        })
        addToPlaylistUri = null
    }
    var refresh by remember { mutableIntStateOf(0) }
    var favoriteUris by remember {
        mutableStateOf(libraryPrefs.getStringSet("favorites", emptySet())?.toSet() ?: emptySet())
    }
    var recentUris by remember {
        mutableStateOf(libraryPrefs.getString("recent", "")?.split("|")?.filter { it.isNotBlank() } ?: emptyList())
    }
    fun markRecentlyPlayed(uri: Uri) {
        val updated = (listOf(uri.toString()) + recentUris.filterNot { it == uri.toString() }).take(50)
        recentUris = updated
        libraryPrefs.edit().putString("recent", updated.joinToString("|")).apply()
    }
    fun toggleFavorite(uri: Uri) {
        val key = uri.toString()
        val updated = if (key in favoriteUris) favoriteUris - key else favoriteUris + key
        favoriteUris = updated
        libraryPrefs.edit().putStringSet("favorites", updated).apply()
    }
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
            libraryViewModel.refresh()
        } else {
            libraryViewModel.clearForPermissionDenied()
            entries = emptyList()
        }
    }

    LaunchedEffect(libraryRecords) {
        entries = libraryRecords.map { record ->
            val media = record.media
            MediaEntry(
                uri = Uri.parse(media.uri),
                name = media.title,
                durationMs = media.duration,
                sizeBytes = media.sizeBytes,
                dateAdded = media.dateAdded,
                isVideo = media.isVideo,
                bucketId = media.bucketId,
                bucketName = media.bucketName
            )
        }
    }

    val shown = remember(entries, tab, query, favoriteUris, recentUris, sortMode) {
        val filtered = entries.filter {
            (tab == LibraryTab.ALL ||
                (tab == LibraryTab.VIDEOS && it.isVideo) ||
                (tab == LibraryTab.AUDIO && !it.isVideo) ||
                (tab == LibraryTab.FAVORITES && it.uri.toString() in favoriteUris) ||
                (tab == LibraryTab.RECENT && it.uri.toString() in recentUris) || tab == LibraryTab.PLAYLISTS) &&
                it.name.contains(query.trim(), ignoreCase = true)
        }
        if (tab == LibraryTab.RECENT) {
            filtered.sortedBy { recentUris.indexOf(it.uri.toString()).let { index -> if (index < 0) Int.MAX_VALUE else index } }
        } else when (sortMode) {
            SortMode.NEWEST -> filtered.sortedByDescending { it.dateAdded }
            SortMode.OLDEST -> filtered.sortedBy { it.dateAdded }
            SortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
            SortMode.LONGEST -> filtered.sortedByDescending { it.durationMs }
            SortMode.LARGEST -> filtered.sortedByDescending { it.sizeBytes }
        }
    }


    if (showCreatePlaylist) {
        AlertDialog(
            onDismissRequest = { showCreatePlaylist = false },
            title = { Text("إنشاء قائمة تشغيل") },
            text = { OutlinedTextField(value = playlistNameInput, onValueChange = { playlistNameInput = it }, singleLine = true, label = { Text("اسم القائمة") }) },
            confirmButton = { TextButton(onClick = { createPlaylist() }) { Text("إنشاء") } },
            dismissButton = { TextButton(onClick = { showCreatePlaylist = false }) { Text("إلغاء") } }
        )
    }
    addToPlaylistUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { addToPlaylistUri = null },
            title = { Text("إضافة إلى قائمة تشغيل") },
            text = {
                Column {
                    if (playlists.isEmpty()) Text("أنشئ قائمة أولاً لإضافة المقاطع إليها.")
                    playlists.forEach { playlist ->
                        TextButton(onClick = { addMediaToPlaylist(uri, playlist.name) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${playlist.name}  (${playlist.uris.size})")
                        }
                    }
                    TextButton(onClick = { addToPlaylistUri = null; showCreatePlaylist = true }) { Text("إنشاء قائمة جديدة") }
                }
            },
            confirmButton = { TextButton(onClick = { addToPlaylistUri = null }) { Text("إغلاق") } }
        )
    }

    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val useDarkTheme = when (playerSettings.theme) {
        "dark" -> true
        "system" -> systemDark
        else -> false
    }
    MaterialTheme(colorScheme = if (useDarkTheme) MalekPlayDarkColors else MalekPlayLightColors) {
        if (showSettings) {
            SettingsDialog(
                settings = playerSettings,
                onSettingsChange = settingsViewModel::update,
                onChildModeChanged = { childModeEnabled = it },
                onDismiss = { showSettings = false }
            )
        }
        if (showNetworkSources) {
            NetworkSourcesDialog(
                onDismiss = { showNetworkSources = false },
                onPlayNetworkMedia = { uri, title ->
                    val extension = uri.lastPathSegment.orEmpty().substringAfterLast('.', "").lowercase()
                    val videoExtensions = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "3gp", "ts", "mpeg", "mpg")
                    val networkEntry = MediaEntry(
                        uri, title, 0L, 0L, System.currentTimeMillis() / 1000L,
                        extension in videoExtensions
                    )
                    queueUris = listOf(uri)
                    queueStartIndex = 0
                    selected = networkEntry
                    markRecentlyPlayed(uri)
                    showNetworkSources = false
                }
            )
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFF176B5B)) {
                        Text("M", Modifier.padding(horizontal = 15.dp, vertical = 8.dp),
                            color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Black)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { showNetworkSources = true }) {
                            Icon(Icons.Default.FolderOpen, "مصادر الشبكة", tint = Color(0xFF176B5B))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, "الإعدادات", tint = Color(0xFF176B5B))
                        }
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
                            PlayerScreen(uri = item.uri, isVideo = item.isVideo, title = item.name, queueUris = queueUris, startIndex = queueStartIndex, isFullscreen = isFullscreen, onToggleFullscreen = setFullscreen, resumePlayback = playerSettings.resumePlayback, defaultPlaybackSpeed = playerSettings.defaultPlaybackSpeed, rememberBrightness = playerSettings.rememberBrightness, subtitleSizeSp = playerSettings.subtitleSizeSp)
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
                    if (!childModeEnabled) {
                        TextButton(onClick = { showCreatePlaylist = true }) {
                            Icon(Icons.Default.Add, null)
                            Text("قائمة جديدة")
                        }
                    }
                    TextButton(onClick = { tab = LibraryTab.PLAYLISTS; activePlaylist = null }) {
                        Icon(Icons.Default.PlaylistPlay, null)
                        Text("قوائم التشغيل")
                    }
                    if (!childModeEnabled) {
                        TextButton(onClick = { filePicker.launch(arrayOf("video/*", "audio/*")) }) {
                            Icon(Icons.Default.FolderOpen, null)
                            Spacer(Modifier.width(4.dp))
                            Text("فتح ملف")
                        }
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
                libraryLoadError?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        color = Color(0xFFB34B42),
                        fontSize = 12.sp
                    )
                }
                Spacer(Modifier.height(5.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box {
                        TextButton(onClick = { sortMenu = true }) {
                            Text("ترتيب حسب: " + when (sortMode) {
                                SortMode.NEWEST -> "الأحدث"
                                SortMode.OLDEST -> "الأقدم"
                                SortMode.NAME -> "الاسم"
                                SortMode.LONGEST -> "المدة"
                                SortMode.LARGEST -> "الحجم"
                            })
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            listOf(
                                SortMode.NEWEST to "الأحدث أولاً",
                                SortMode.OLDEST to "الأقدم أولاً",
                                SortMode.NAME to "الاسم أبجدياً",
                                SortMode.LONGEST to "الأطول مدة",
                                SortMode.LARGEST to "الأكبر حجماً"
                            ).forEach { (mode, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        sortMode = mode
                                        context.getSharedPreferences("malekplay_library", Context.MODE_PRIVATE)
                                            .edit().putString("sort_mode", mode.name).apply()
                                        sortMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryTab.values().forEach { choice ->
                        val label = when (choice) {
                            LibraryTab.ALL -> "الكل"
                            LibraryTab.VIDEOS -> "الفيديوهات"
                            LibraryTab.AUDIO -> "الصوتيات"
                            LibraryTab.FAVORITES -> "المفضلة"
                            LibraryTab.RECENT -> "الأخيرة"
                            LibraryTab.FOLDERS -> "المجلدات"
                            LibraryTab.PLAYLISTS -> "القوائم"
                        }
                        Surface(
                            Modifier.width(82.dp).clip(RoundedCornerShape(11.dp)).clickable { tab = choice; if (choice == LibraryTab.FOLDERS) activeFolderId = null },
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

                    tab == LibraryTab.FOLDERS -> {
                        val folderGroups = entries
                            .filter { it.name.contains(query.trim(), ignoreCase = true) || query.isBlank() }
                            .groupBy { it.bucketId }
                            .map { (id, files) ->
                                Triple(id, files.firstOrNull()?.bucketName ?: "مجلدات أخرى", files)
                            }
                            .sortedBy { it.second.lowercase() }

                        Column(Modifier.weight(1f)) {
                            if (activeFolderId == null) {
                                Text("مجلدات الجهاز", fontWeight = FontWeight.Bold, fontSize = 17.sp,
                                    modifier = Modifier.padding(vertical = 8.dp))
                                if (folderGroups.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("لا توجد مجلدات وسائط لعرضها", color = Color(0xFF718079))
                                    }
                                } else {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                        items(folderGroups, key = { it.first }) { folder ->
                                            Card(
                                                modifier = Modifier.fillMaxWidth().clickable { activeFolderId = folder.first },
                                                shape = RoundedCornerShape(14.dp),
                                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                                            ) {
                                                Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.FolderOpen, null, tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(30.dp))
                                                    Spacer(Modifier.width(12.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(folder.second, fontWeight = FontWeight.Bold)
                                                        Text("${folder.third.size} ملف", color = Color(0xFF718079), fontSize = 12.sp)
                                                    }
                                                    Icon(Icons.Default.PlayCircle, null, tint = Color(0xFF176B5B))
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                val folder = folderGroups.firstOrNull { it.first == activeFolderId }
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { activeFolderId = null }) { Text("رجوع للمجلدات") }
                                    Spacer(Modifier.weight(1f))
                                    Text(folder?.second ?: "المجلد", fontWeight = FontWeight.Bold)
                                }
                                val folderFiles = entries.filter {
                                    it.bucketId == activeFolderId &&
                                        it.name.contains(query.trim(), ignoreCase = true)
                                }
                                if (folderFiles.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("هذا المجلد فارغ", color = Color(0xFF718079))
                                    }
                                } else {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(folderFiles, key = { it.uri.toString() }) { entry ->
                                            MediaRow(
                                                entry = entry,
                                                selected = selected?.uri == entry.uri,
                                                favorite = entry.uri.toString() in favoriteUris,
                                                onFavoriteClick = { toggleFavorite(entry.uri) },
                                                onAddPlaylist = { addToPlaylistUri = entry.uri },
                                                onClick = {
                                                    queueUris = folderFiles.map { it.uri }
                                                    queueStartIndex = folderFiles.indexOf(entry)
                                                    selected = entry
                                                    markRecentlyPlayed(entry.uri)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    tab == LibraryTab.PLAYLISTS -> {
                        val currentPlaylist = playlists.firstOrNull { it.name == activePlaylist }
                        Column(Modifier.weight(1f)) {
                            if (currentPlaylist == null) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("قوائمك الموسيقية", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                    Button(onClick = { showCreatePlaylist = true }) { Text("إنشاء قائمة") }
                                }
                                if (playlists.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("لم تنشئ أي قائمة تشغيل بعد", color = Color(0xFF718079))
                                    }
                                } else {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(playlists, key = { it.name }) { playlist ->
                                            Card(Modifier.fillMaxWidth().clickable { activePlaylist = playlist.name },
                                                colors = CardDefaults.cardColors(containerColor = Color.White)) {
                                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(Icons.Default.PlaylistPlay, null, tint = Color(0xFF176B5B))
                                                    Spacer(Modifier.width(10.dp))
                                                    Column(Modifier.weight(1f)) {
                                                        Text(playlist.name, fontWeight = FontWeight.Bold)
                                                        Text("${playlist.uris.size} مقطع", color = Color(0xFF718079), fontSize = 12.sp)
                                                    }
                                                    IconButton(onClick = { persistPlaylists(playlists.filterNot { it.name == playlist.name }) }) {
                                                        Icon(Icons.Default.Delete, "حذف القائمة", tint = Color(0xFFB34B42))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { activePlaylist = null }) { Text("رجوع للقوائم") }
                                    Spacer(Modifier.weight(1f))
                                    Text(currentPlaylist.name, fontWeight = FontWeight.Bold)
                                }
                                val playlistEntries = currentPlaylist.uris.mapNotNull { key -> entries.firstOrNull { it.uri.toString() == key } }
                                if (playlistEntries.isEmpty()) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("هذه القائمة فارغة أو بعض ملفاتها لم تعد متاحة")
                                    }
                                } else {
                                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(playlistEntries, key = { it.uri.toString() }) { entry ->
                                            MediaRow(entry, selected?.uri == entry.uri, entry.uri.toString() in favoriteUris,
                                                { toggleFavorite(entry.uri) },
                                                { persistPlaylists(playlists.map { p ->
                                                    if (p.name == currentPlaylist.name) p.copy(uris = p.uris - entry.uri.toString()) else p
                                                }) },
                                                {
                                                    queueUris = playlistEntries.map { it.uri }
                                                    queueStartIndex = playlistEntries.indexOf(entry)
                                                    selected = entry
                                                    markRecentlyPlayed(entry.uri)
                                                })
                                        }
                                    }
                                }
                            }
                        }
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
                            MediaRow(
                                entry = entry,
                                selected = selected?.uri == entry.uri,
                                favorite = entry.uri.toString() in favoriteUris,
                                onFavoriteClick = { toggleFavorite(entry.uri) },
                                onAddPlaylist = { addToPlaylistUri = entry.uri },
                                onClick = {
                                    queueUris = listOf(entry.uri)
                                    queueStartIndex = 0
                                    selected = entry
                                    markRecentlyPlayed(entry.uri)
                                }
                            )
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


private fun loadPlaylists(raw: String): List<UserPlaylist> = runCatching {
    val array = org.json.JSONArray(raw)
    (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val name = item.optString("name").trim()
        val tracks = item.optJSONArray("uris") ?: org.json.JSONArray()
        if (name.isBlank()) null else UserPlaylist(name, (0 until tracks.length()).map { tracks.optString(it) }.filter { it.isNotBlank() })
    }
}.getOrDefault(emptyList())

private fun savePlaylists(playlists: List<UserPlaylist>): String {
    val array = org.json.JSONArray()
    playlists.forEach { playlist ->
        val item = org.json.JSONObject().put("name", playlist.name)
        val tracks = org.json.JSONArray()
        playlist.uris.forEach { tracks.put(it) }
        item.put("uris", tracks)
        array.put(item)
    }
    return array.toString()
}

private fun libraryPrefsValue(context: Context, key: String, fallback: String): String =
    context.getSharedPreferences("malekplay_library", Context.MODE_PRIVATE).getString(key, fallback) ?: fallback

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
private fun MediaRow(
    entry: MediaEntry,
    selected: Boolean,
    favorite: Boolean,
    onFavoriteClick: () -> Unit,
    onAddPlaylist: () -> Unit,
    onClick: () -> Unit
) {
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
            IconButton(onClick = onAddPlaylist) {
                Icon(Icons.Default.PlaylistAdd, contentDescription = "إضافة إلى قائمة تشغيل", tint = Color(0xFF176B5B))
            }
            IconButton(onClick = onFavoriteClick) {
                Icon(
                    if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (favorite) "إزالة من المفضلة" else "إضافة إلى المفضلة",
                    tint = if (favorite) Color(0xFFD39A24) else Color(0xFF87958D)
                )
            }
            Spacer(Modifier.width(4.dp))
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
