package com.malekplay.ui.kids

import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.malekplay.R
import com.malekplay.core.ui.theme.contrastingOnColor
import com.malekplay.core.ui.util.formatDuration
import com.malekplay.domain.model.LibraryItem
import com.malekplay.domain.model.MediaItem
import com.malekplay.safety.ParentalControlManager
import com.malekplay.ui.components.MediaThumbnail
import com.malekplay.ui.library.LibraryViewModel
import com.malekplay.ui.player.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun KidsModeScreen(
    onBack: () -> Unit,
    onPlay: (MediaItem) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val manager = remember(context) { ParentalControlManager(context) }
    val enabled = manager.isEnabled
    val playerController by playerViewModel.controller.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    // A previous media session may outlive the Activity. Always clear it when Kids Mode opens.
    LaunchedEffect(enabled, playerController) {
        if (enabled && playerController != null) playerViewModel.stopForParentalMode()
    }

    // Live countdown for session timer + lockout ticks while Kids Mode is active.
    var remainingSeconds by remember(manager, enabled) {
        mutableIntStateOf(manager.remainingSessionSeconds())
    }
    var timerExpired by remember(manager, enabled) {
        mutableStateOf(manager.isSessionExpired())
    }
    LaunchedEffect(enabled) {
        if (enabled) {
            while (true) {
                remainingSeconds = manager.remainingSessionSeconds()
                timerExpired = manager.isSessionExpired()
                delay(1_000L)
            }
        }
    }

    val media by viewModel.allMedia.collectAsStateWithLifecycle()
    var selected by remember(manager) { mutableStateOf(manager.allowedMediaIds) }
    var searchQuery by remember { mutableStateOf("") }
    var mediaFilter by remember { mutableStateOf("all") }
    var selectedFolder by remember { mutableStateOf<String?>(null) }
    var kidsGrid by remember { mutableStateOf(true) }
    var showParentDialog by remember { mutableStateOf(false) }
    var parentPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }

    val sortedMedia by produceState(
        initialValue = emptyList<LibraryItem>(), media
    ) {
        value = withContext(Dispatchers.Default) {
            media.sortedBy { it.media.title.lowercase() }
        }
    }

    val folders by produceState(initialValue = emptyList<String>(), sortedMedia) {
        value = withContext(Dispatchers.Default) {
            sortedMedia.mapNotNull { item ->
                item.media.folderName.takeIf { it.isNotBlank() }
            }.distinct().sorted()
        }
    }

    val approvedMedia by produceState(
        initialValue = emptyList<LibraryItem>(), sortedMedia, selected
    ) {
        value = withContext(Dispatchers.Default) {
            sortedMedia.filter { it.media.id in selected }
        }
    }

    val filteredLibraryMedia by produceState(
        initialValue = emptyList<LibraryItem>(), sortedMedia, searchQuery, mediaFilter, selectedFolder
    ) {
        value = withContext(Dispatchers.Default) {
            sortedMedia.filter { item ->
                val matchesType = when (mediaFilter) {
                    "video" -> item.media.isVideo
                    "audio" -> !item.media.isVideo
                    else -> true
                }
                val matchesFolder = selectedFolder == null || item.media.folderName == selectedFolder
                val matchesQuery = searchQuery.isBlank() ||
                    item.media.title.contains(searchQuery, ignoreCase = true) ||
                    item.media.folderName.contains(searchQuery, ignoreCase = true)
                matchesType && matchesFolder && matchesQuery
            }
        }
    }

    // Children cannot leave Kids Mode with the system Back button.
    BackHandler(enabled = enabled) {
        showParentDialog = true
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = colors.primaryContainer,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Filled.ChildCare,
                                    contentDescription = null,
                                    tint = colors.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Text(
                            text = stringResource(
                                if (enabled) R.string.kids_library_title else R.string.kids_choose_title
                            ),
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    if (!enabled) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    }
                },
                actions = {
                    if (enabled) {
                        IconButton(onClick = { kidsGrid = !kidsGrid }) {
                            Icon(
                                imageVector = if (kidsGrid) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                                contentDescription = null
                            )
                        }
                        FilledTonalButton(
                            onClick = { showParentDialog = true },
                            modifier = Modifier.padding(end = 8.dp),
                            shape = CircleShape,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.kids_parent_access),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    } else {
                        Button(
                            onClick = {
                                if (manager.saveAllowedMediaIds(selected)) {
                                    saveError = false
                                    onBack()
                                } else {
                                    saveError = true
                                }
                            },
                            modifier = Modifier.padding(end = 8.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = colors.primary,
                                contentColor = contrastingOnColor(colors.primary)
                            )
                        ) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = "${stringResource(R.string.kids_save_selection)} (${selected.size})",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            colors.primaryContainer.copy(alpha = if (enabled) 0.28f else 0.16f),
                            colors.background,
                            colors.surface
                        )
                    )
                )
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            if (enabled) {
                // Kids friendly banner + timer pill
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = colors.primaryContainer.copy(alpha = 0.65f),
                    border = BorderStroke(1.dp, colors.primary.copy(alpha = 0.28f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.kids_library_summary, approvedMedia.size),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.onPrimaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                            if (manager.sessionLimitMinutes > 0 && remainingSeconds < Int.MAX_VALUE) {
                                val mm = remainingSeconds / 60
                                val ss = remainingSeconds % 60
                                val formatted = "%02d:%02d".format(mm, ss)
                                Spacer(modifier = Modifier.height(3.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.Timer,
                                        contentDescription = null,
                                        tint = if (remainingSeconds <= 60) colors.error else colors.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(R.string.kids_timer_remaining, formatted),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (remainingSeconds <= 60) colors.error else colors.onPrimaryContainer,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        Icon(
                            imageVector = Icons.Filled.ChildCare,
                            contentDescription = null,
                            tint = colors.primary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                if (timerExpired) {
                    // Watch-time limit reached: block playback until parent extends or exits.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        ElevatedCard(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainerHigh),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, colors.primary.copy(alpha = 0.25f), RoundedCornerShape(24.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = colors.primaryContainer,
                                    modifier = Modifier.size(64.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.HourglassBottom,
                                            contentDescription = null,
                                            tint = colors.primary,
                                            modifier = Modifier.size(34.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = stringResource(R.string.kids_timer_expired_title),
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = stringResource(R.string.kids_timer_expired_body),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Button(
                                    onClick = { showParentDialog = true },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(R.string.kids_parent_access))
                                }
                            }
                        }
                    }
                } else if (approvedMedia.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ChildCare,
                                contentDescription = null,
                                tint = colors.primary,
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = stringResource(R.string.kids_no_approved_media),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else if (kidsGrid) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 156.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(approvedMedia, key = { it.media.id }) { item ->
                            KidsMediaGridCard(item = item.media, onClick = { onPlay(item.media) })
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(approvedMedia, key = { it.media.id }) { item ->
                            KidsMediaListCard(item = item.media, onClick = { onPlay(item.media) })
                        }
                    }
                }
            } else {
                // Parent selection screen
                Text(
                    text = stringResource(R.string.kids_choose_summary, selected.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant
                )
                if (saveError) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.kids_save_error),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.lib_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = null)
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = mediaFilter == "all",
                        onClick = { mediaFilter = "all" },
                        label = { Text(stringResource(R.string.settings_section_all)) }
                    )
                    FilterChip(
                        selected = mediaFilter == "video",
                        onClick = { mediaFilter = "video" },
                        label = { Text(stringResource(R.string.kids_video_label)) }
                    )
                    FilterChip(
                        selected = mediaFilter == "audio",
                        onClick = { mediaFilter = "audio" },
                        label = { Text(stringResource(R.string.kids_audio_label)) }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    FilledTonalButton(
                        onClick = {
                            selected = selected + filteredLibraryMedia.map { it.media.id }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Filled.SelectAll, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.kids_select_all), style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { selected = emptySet() },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(R.string.kids_clear_all), style = MaterialTheme.typography.labelMedium)
                    }
                }
                if (folders.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = selectedFolder == null,
                            onClick = { selectedFolder = null },
                            leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null, modifier = Modifier.size(15.dp)) },
                            label = { Text(stringResource(R.string.lib_title_folders)) }
                        )
                        folders.forEach { folder ->
                            FilterChip(
                                selected = selectedFolder == folder,
                                onClick = { selectedFolder = if (selectedFolder == folder) null else folder },
                                label = { Text(folder, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (filteredLibraryMedia.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = stringResource(R.string.kids_no_media_found),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            FilledTonalButton(onClick = { viewModel.refresh() }) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.kids_refresh_library))
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(filteredLibraryMedia, key = { it.media.id }) { item ->
                            val isSelected = item.media.id in selected
                            val toggle = {
                                selected = if (isSelected) selected - item.media.id else selected + item.media.id
                            }
                            ElevatedCard(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.elevatedCardColors(
                                    containerColor = if (isSelected) {
                                        colors.primaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        colors.surfaceContainer.copy(alpha = 0.90f)
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(
                                        1.dp,
                                        if (isSelected) colors.primary.copy(alpha = 0.5f) else colors.outlineVariant.copy(alpha = 0.35f),
                                        RoundedCornerShape(16.dp)
                                    )
                                    .clickable(onClick = toggle)
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    MediaThumbnail(
                                        media = item.media,
                                        modifier = Modifier
                                            .width(76.dp)
                                            .height(50.dp),
                                        targetSize = Size(160, 100),
                                        cornerRadius = 10.dp
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.media.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        val meta = buildString {
                                            append(
                                                if (item.media.isVideo) stringResource(R.string.kids_video_label)
                                                else stringResource(R.string.kids_audio_label)
                                            )
                                            if (item.media.durationMs > 0) {
                                                append(" • ")
                                                append(formatDuration(item.media.durationMs))
                                            }
                                            if (item.media.folderName.isNotBlank()) {
                                                append(" • ")
                                                append(item.media.folderName)
                                            }
                                        }
                                        Text(
                                            text = meta,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            selected = if (checked) selected + item.media.id else selected - item.media.id
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showParentDialog) {
        val lockoutSeconds = manager.remainingLockoutSeconds()
        AlertDialog(
            onDismissRequest = {
                showParentDialog = false
                parentPin = ""
                pinError = false
            },
            title = { Text(stringResource(R.string.kids_parent_access)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.kids_parent_pin_prompt))
                    OutlinedTextField(
                        value = parentPin,
                        onValueChange = {
                            parentPin = it.filter(Char::isDigit).take(8)
                            pinError = false
                        },
                        label = { Text(stringResource(R.string.kids_pin_hint)) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true
                    )
                    if (lockoutSeconds > 0) {
                        Text(
                            text = stringResource(R.string.kids_pin_lockout, lockoutSeconds),
                            color = colors.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else if (pinError) {
                        Text(
                            text = stringResource(R.string.kids_pin_wrong),
                            color = colors.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (timerExpired) {
                        TextButton(
                            onClick = {
                                if (manager.extendSessionAfterPin(parentPin, 15)) {
                                    remainingSeconds = manager.remainingSessionSeconds()
                                    timerExpired = false
                                    showParentDialog = false
                                    parentPin = ""
                                    pinError = false
                                } else {
                                    pinError = true
                                }
                            }
                        ) {
                            Text(stringResource(R.string.kids_extend_15m))
                        }
                    }
                    Button(
                        onClick = {
                            if (manager.disableAfterPin(parentPin)) {
                                showParentDialog = false
                                parentPin = ""
                                pinError = false
                                onBack()
                            } else {
                                pinError = true
                            }
                        }
                    ) {
                        Text(stringResource(R.string.kids_parent_exit))
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showParentDialog = false
                        parentPin = ""
                        pinError = false
                    }
                ) {
                    Text(stringResource(R.string.lib_cancel))
                }
            }
        )
    }
}

@Composable
private fun KidsMediaGridCard(
    item: MediaItem,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainer.copy(alpha = 0.95f)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.primary.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
            ) {
                MediaThumbnail(
                    media = item,
                    modifier = Modifier.fillMaxSize(),
                    targetSize = Size(320, 200),
                    cornerRadius = 0.dp
                )
                // Play pill badge
                Surface(
                    shape = CircleShape,
                    color = colors.primary.copy(alpha = 0.90f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = contrastingOnColor(colors.primary),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                if (item.durationMs > 0) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Black.copy(alpha = 0.72f),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                    ) {
                        Text(
                            text = formatDuration(item.durationMs),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = if (item.isVideo) Icons.Filled.Movie else Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = stringResource(if (item.isVideo) R.string.kids_video_label else R.string.kids_audio_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun KidsMediaListCard(
    item: MediaItem,
    onClick: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    ElevatedCard(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = colors.surfaceContainer.copy(alpha = 0.95f)),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.primary.copy(alpha = 0.20f), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MediaThumbnail(
                media = item,
                modifier = Modifier
                    .width(96.dp)
                    .height(60.dp),
                targetSize = Size(200, 125),
                cornerRadius = 12.dp
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val subtitle = buildString {
                    append(
                        if (item.isVideo) stringResource(R.string.kids_video_label)
                        else stringResource(R.string.kids_audio_label)
                    )
                    if (item.durationMs > 0) {
                        append(" • ")
                        append(formatDuration(item.durationMs))
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant
                )
            }
            Surface(
                shape = CircleShape,
                color = colors.primary,
                modifier = Modifier.size(38.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = contrastingOnColor(colors.primary),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
