package com.malekplay.ui.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.malekplay.R
import com.malekplay.vault.PrivateVaultTreeSupport
import com.malekplay.vault.SelectedVaultBrowserItem
import com.malekplay.vault.VaultBrowserItem
import com.malekplay.vault.VaultBrowserSelectionManager
import com.malekplay.vault.VaultBrowserSort
import com.malekplay.vault.filterAndSortBrowserItems
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultInternalBrowserModal(
    treeSupport: PrivateVaultTreeSupport,
    rootTreeUri: Uri?,
    busy: Boolean,
    onTouchActivity: () -> Unit,
    onPickRootFolder: () -> Unit,
    onOpenSystemPickerFallback: () -> Unit,
    onCopyOrMoveSelection: (List<SelectedVaultBrowserItem>, move: Boolean) -> Unit,
    onImportSubfolder: (treeUri: Uri, folderDocId: String, parentDocId: String?, folderName: String, displayPath: String, move: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pathStack = remember(rootTreeUri) { mutableStateListOf<Pair<String, String>>() }
    val rawItems = remember(rootTreeUri) { mutableStateListOf<VaultBrowserItem>() }
    val childCounts = remember(rootTreeUri) { mutableStateMapOf<String, Int?>() }
    val selectionManager = remember { VaultBrowserSelectionManager() }
    var selectionVersion by remember { mutableIntStateOf(0) }

    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(VaultBrowserSort.NAME) }
    var ascending by remember { mutableStateOf(true) }
    var pendingSubfolderImport by remember { mutableStateOf<VaultBrowserItem?>(null) }

    val thumbCache = remember {
        object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
        }
    }

    DisposableEffect(Unit) {
        onDispose { thumbCache.evictAll() }
    }

    fun buildCurrentDisplayPath(rootLabel: String): String {
        if (pathStack.isEmpty()) return rootLabel
        return buildString {
            append(rootLabel)
            pathStack.forEach { append(" / ").append(it.second) }
        }
    }

    val rootLabel = stringResource(R.string.vault_root_crumb)

    fun loadFolder(folderDocId: String?) {
        val tree = rootTreeUri ?: return
        onTouchActivity()
        scope.launch {
            loading = true
            loadError = null
            runCatching {
                withContext(Dispatchers.IO) {
                    treeSupport.listBrowserChildren(tree, folderDocId)
                }
            }.onSuccess { loaded ->
                rawItems.clear()
                rawItems.addAll(loaded)
            }.onFailure { err ->
                rawItems.clear()
                loadError = err.localizedMessage
            }
            loading = false
        }
    }

    LaunchedEffect(rootTreeUri) {
        if (rootTreeUri != null) {
            pathStack.clear()
            loadFolder(null)
        }
    }

    val visibleItems = remember(rawItems.toList(), searchQuery, sortBy, ascending) {
        filterAndSortBrowserItems(rawItems.toList(), searchQuery, sortBy, ascending)
    }

    val currentFolderFiles = remember(visibleItems) {
        visibleItems.filter { !it.isDirectory }
    }

    // Recompute snapshots whenever selectionVersion updates
    val selectedSnapshot = remember(selectionVersion) { selectionManager.selectedItems() }
    val selectedCount = selectedSnapshot.size
    val selectedTotalBytes = remember(selectionSnapshot) {
        selectedSnapshot.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    }
    val allCurrentFilesSelected = remember(currentFolderFiles, selectionVersion) {
        selectionManager.areAllVisibleFilesSelected(currentFolderFiles)
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(enabled = !busy) {
            if (pathStack.isNotEmpty()) {
                pathStack.removeAt(pathStack.lastIndex)
                loadFolder(pathStack.lastOrNull()?.first)
            } else {
                onDismiss()
            }
        }

        Scaffold(
            containerColor = Color(0xFF0D0D12),
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF13131C),
                        titleContentColor = Color.White
                    ),
                    modifier = Modifier.statusBarsPadding(),
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                onTouchActivity()
                                if (pathStack.isNotEmpty()) {
                                    pathStack.removeAt(pathStack.lastIndex)
                                    loadFolder(pathStack.lastOrNull()?.first)
                                } else {
                                    onDismiss()
                                }
                            },
                            enabled = !busy
                        ) {
                            Icon(
                                imageVector = if (pathStack.isNotEmpty()) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                                contentDescription = stringResource(R.string.common_close),
                                tint = Color.White
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = pathStack.lastOrNull()?.second ?: stringResource(R.string.vault_browser_title),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.vault_browser_subtitle),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFA8A3BC)
                            )
                        }
                    },
                    actions = {
                        if (rootTreeUri != null) {
                            IconButton(
                                onClick = {
                                    onTouchActivity()
                                    onPickRootFolder()
                                },
                                enabled = !busy
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = stringResource(R.string.vault_browser_change_folder),
                                    tint = Color(0xFFA855F7)
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                onTouchActivity()
                                onDismiss()
                            },
                            enabled = !busy
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.common_close),
                                tint = Color.White
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (selectedCount > 0) {
                    Surface(
                        color = Color(0xFF171724),
                        tonalElevation = 8.dp,
                        border = BorderStroke(1.dp, Color(0xFF2D2742)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.vault_browser_selected_summary,
                                        selectedCount,
                                        formatVaultBytes(context, selectedTotalBytes)
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFFD8B4FE),
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(
                                    onClick = {
                                        onTouchActivity()
                                        selectionManager.clear()
                                        selectionVersion++
                                    },
                                    enabled = !busy
                                ) {
                                    Text(stringResource(R.string.vault_deselect_all))
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onTouchActivity()
                                        onCopyOrMoveSelection(selectedSnapshot, false)
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    border = BorderStroke(1.dp, Color(0xFFA855F7))
                                ) {
                                    Icon(
                                        Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.vault_browser_copy_to_vault),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Button(
                                    onClick = {
                                        onTouchActivity()
                                        onCopyOrMoveSelection(selectedSnapshot, true)
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFA855F7),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.DriveFileMove,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.vault_browser_move_to_vault),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            if (rootTreeUri == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = Color(0xFF161622),
                        border = BorderStroke(1.dp, Color(0xFF33274D)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Color(0xFFA855F7).copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.FolderOpen,
                                    contentDescription = null,
                                    tint = Color(0xFFA855F7),
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Text(
                                text = stringResource(R.string.vault_browser_setup_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.vault_browser_setup_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFFB5B0C9)
                            )
                            Button(
                                onClick = {
                                    onTouchActivity()
                                    onPickRootFolder()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFA855F7),
                                    contentColor = Color.White
                                )
                            ) {
                                Text(stringResource(R.string.vault_browser_pick_root))
                            }
                            OutlinedButton(
                                onClick = {
                                    onTouchActivity()
                                    onOpenSystemPickerFallback()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.vault_browser_system_picker_fallback))
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Breadcrumb trail
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = pathStack.isEmpty(),
                            onClick = {
                                onTouchActivity()
                                if (pathStack.isNotEmpty()) {
                                    pathStack.clear()
                                    loadFolder(null)
                                }
                            },
                            label = { Text(rootLabel) }
                        )
                        pathStack.forEachIndexed { index, pair ->
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = Color(0xFF7D7794),
                                modifier = Modifier.size(18.dp)
                            )
                            FilterChip(
                                selected = index == pathStack.lastIndex,
                                onClick = {
                                    onTouchActivity()
                                    if (index < pathStack.lastIndex) {
                                        while (pathStack.size > index + 1) {
                                            pathStack.removeAt(pathStack.lastIndex)
                                        }
                                        loadFolder(pair.first)
                                    }
                                },
                                label = { Text(pair.second, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            )
                        }
                    }

                    // Search + Select All row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                onTouchActivity()
                                searchQuery = it
                            },
                            placeholder = { Text(stringResource(R.string.vault_browser_search_hint)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(onClick = { searchQuery = "" }) {
                                        Icon(Icons.Filled.Close, contentDescription = null)
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                onTouchActivity()
                                if (allCurrentFilesSelected) {
                                    selectionManager.deselectAll(currentFolderFiles)
                                } else {
                                    val parentDoc = pathStack.lastOrNull()?.first
                                    val parentPath = buildCurrentDisplayPath(rootLabel)
                                    selectionManager.selectAll(
                                        items = currentFolderFiles,
                                        treeUri = rootTreeUri,
                                        parentDocumentId = parentDoc,
                                        displayFolderPath = parentPath
                                    )
                                }
                                selectionVersion++
                            },
                            enabled = currentFolderFiles.isNotEmpty() && !busy
                        ) {
                            Icon(
                                imageVector = if (allCurrentFilesSelected) Icons.Filled.CheckBox else Icons.Filled.SelectAll,
                                contentDescription = stringResource(
                                    if (allCurrentFilesSelected) R.string.vault_deselect_all else R.string.vault_select_all
                                ),
                                tint = if (allCurrentFilesSelected) Color(0xFFA855F7) else Color.White
                            )
                        }
                    }

                    // Sort bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        VaultBrowserSort.entries.forEach { option ->
                            val labelRes = when (option) {
                                VaultBrowserSort.NAME -> R.string.vault_sort_name
                                VaultBrowserSort.SIZE -> R.string.vault_sort_size
                                VaultBrowserSort.DATE -> R.string.vault_browser_sort_date
                            }
                            FilterChip(
                                selected = sortBy == option,
                                onClick = {
                                    onTouchActivity()
                                    sortBy = option
                                },
                                label = { Text(stringResource(labelRes)) }
                            )
                        }
                        FilterChip(
                            selected = false,
                            onClick = {
                                onTouchActivity()
                                ascending = !ascending
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (ascending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                    contentDescription = stringResource(R.string.vault_sort_order_toggle),
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            label = {
                                Text(
                                    stringResource(
                                        if (ascending) R.string.vault_browser_sort_ascending
                                        else R.string.vault_browser_sort_descending
                                    )
                                )
                            }
                        )
                        TextButton(
                            onClick = {
                                onTouchActivity()
                                onOpenSystemPickerFallback()
                            },
                            enabled = !busy
                        ) {
                            Text(stringResource(R.string.vault_browser_system_picker_fallback))
                        }
                    }

                    if (loading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFA855F7)
                        )
                    }

                    loadError?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    if (!loading && visibleItems.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (rawItems.isEmpty()) {
                                    stringResource(R.string.vault_browser_empty_folder)
                                } else {
                                    stringResource(R.string.vault_browser_no_matches)
                                },
                                color = Color(0xFFA8A3BC),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = if (selectedCount > 0) 96.dp else 16.dp)
                        ) {
                            items(visibleItems, key = { it.documentId }) { item ->
                                if (item.isDirectory) {
                                    LaunchedEffect(item.documentId, rootTreeUri) {
                                        if (!childCounts.containsKey(item.documentId)) {
                                            val computed = withContext(Dispatchers.IO) {
                                                treeSupport.countDirectChildren(rootTreeUri, item.documentId)
                                            }
                                            childCounts[item.documentId] = computed
                                        }
                                    }
                                    val count = childCounts[item.documentId] ?: item.childCount
                                    ElevatedCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !busy) {
                                                onTouchActivity()
                                                pathStack.add(item.documentId to item.displayName)
                                                loadFolder(item.documentId)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color(0xFFA855F7).copy(alpha = 0.16f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Folder,
                                                    contentDescription = null,
                                                    tint = Color(0xFFA855F7),
                                                    modifier = Modifier.size(28.dp)
                                                )
                                            }
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.displayName,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = if (count != null) {
                                                        stringResource(R.string.vault_browser_folder_items_count, count)
                                                    } else {
                                                        stringResource(R.string.vault_browser_folder_label)
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    onTouchActivity()
                                                    pendingSubfolderImport = item
                                                },
                                                enabled = !busy
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.CreateNewFolder,
                                                    contentDescription = stringResource(R.string.vault_browser_import_folder_btn),
                                                    tint = Color(0xFFA855F7)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    val isSelected = remember(selectionVersion, item.uri) {
                                        selectionManager.contains(item.uri)
                                    }
                                    val parentDoc = pathStack.lastOrNull()?.first
                                    val displayPath = buildCurrentDisplayPath(rootLabel)
                                    ElevatedCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !busy) {
                                                onTouchActivity()
                                                selectionManager.toggle(
                                                    item = item,
                                                    treeUri = rootTreeUri,
                                                    parentDocumentId = parentDoc,
                                                    displayFolderPath = displayPath
                                                )
                                                selectionVersion++
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = isSelected,
                                                onCheckedChange = { checked ->
                                                    onTouchActivity()
                                                    if (checked) {
                                                        selectionManager.select(
                                                            item = item,
                                                            treeUri = rootTreeUri,
                                                            parentDocumentId = parentDoc,
                                                            displayFolderPath = displayPath
                                                        )
                                                    } else {
                                                        selectionManager.deselectAll(listOf(item))
                                                    }
                                                    selectionVersion++
                                                },
                                                enabled = !busy
                                            )
                                            BrowserItemThumbnail(
                                                item = item,
                                                cache = thumbCache
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.displayName,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                val dateSuffix = if (item.lastModified > 0L) {
                                                    " · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.lastModified))}"
                                                } else ""
                                                Text(
                                                    text = "${formatVaultBytes(context, item.sizeBytes)}$dateSuffix",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val folderCandidate = pendingSubfolderImport
    val activeTree = rootTreeUri
    if (folderCandidate != null && activeTree != null) {
        val currentPathPrefix = buildCurrentDisplayPath(rootLabel)
        val fullSubfolderPath = "$currentPathPrefix / ${folderCandidate.displayName}"
        val parentDocId = pathStack.lastOrNull()?.first
        AlertDialog(
            onDismissRequest = { pendingSubfolderImport = null },
            title = { Text(stringResource(R.string.vault_browser_import_folder_title, folderCandidate.displayName)) },
            text = { Text(stringResource(R.string.vault_browser_import_folder_body)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            pendingSubfolderImport = null
                            onImportSubfolder(activeTree, folderCandidate.documentId, parentDocId, folderCandidate.displayName, fullSubfolderPath, false)
                        }
                    ) {
                        Text(stringResource(R.string.vault_browser_copy_to_vault))
                    }
                    TextButton(
                        onClick = {
                            pendingSubfolderImport = null
                            onImportSubfolder(activeTree, folderCandidate.documentId, parentDocId, folderCandidate.displayName, fullSubfolderPath, true)
                        }
                    ) {
                        Text(stringResource(R.string.vault_browser_move_to_vault), color = Color(0xFFA855F7))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSubfolderImport = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun BrowserItemThumbnail(
    item: VaultBrowserItem,
    cache: LruCache<String, Bitmap>
) {
    val context = LocalContext.current
    val cacheKey = item.uri.toString()
    var thumb by remember(cacheKey) { mutableStateOf(cache.get(cacheKey)) }

    val isMediaThumb = item.mimeType.startsWith("image/") || item.mimeType.startsWith("video/")
    LaunchedEffect(cacheKey) {
        if (!isMediaThumb || thumb != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            loadSafeBrowserThumbnail(context, item.uri, item.mimeType)
        }
        if (loaded != null) {
            cache.put(cacheKey, loaded)
            thumb = loaded
        }
    }

    val boxModifier = Modifier
        .size(48.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(Color(0xFF1F1E2D))

    if (thumb != null) {
        Image(
            bitmap = thumb!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = boxModifier
        )
    } else {
        Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when {
                    item.mimeType.startsWith("video/") -> Icons.Filled.VideoFile
                    item.mimeType.startsWith("audio/") -> Icons.Filled.Audiotrack
                    item.mimeType.startsWith("image/") -> Icons.Filled.Image
                    item.mimeType.startsWith("text/") || item.mimeType == "application/pdf" -> Icons.Filled.Description
                    else -> Icons.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = Color(0xFFA855F7)
            )
        }
    }
}

private fun loadSafeBrowserThumbnail(context: Context, uri: Uri, mimeType: String): Bitmap? {
    val resolver = context.contentResolver
    val signal = CancellationSignal()
    val providerThumb = runCatching {
        DocumentsContract.getDocumentThumbnail(resolver, uri, Point(144, 144), signal)
    }.getOrNull()
    if (providerThumb != null) return providerThumb

    if (mimeType.startsWith("image/")) {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    }

    if (mimeType.startsWith("video/")) {
        val retriever = MediaMetadataRetriever()
        return runCatching {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
            } else {
                val frame = retriever.getFrameAtTime(-1L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                frame?.let {
                    val scaled = Bitmap.createScaledBitmap(it, 144, 144, true)
                    if (scaled !== it) it.recycle()
                    scaled
                }
            }
        }.getOrNull().also {
            runCatching { retriever.release() }
        }
    }
    return null
}

internal fun formatVaultBytes(context: Context, bytes: Long): String {
    if (bytes < 0L) return context.getString(R.string.vault_unknown_size)
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f)
        bytes < 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f))
    }
}
