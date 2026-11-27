package com.malekplay.ui.vault

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.malekplay.R
import com.malekplay.vault.PrivateVaultArchiveBrowser
import com.malekplay.vault.PrivateVaultEntry
import com.malekplay.vault.PrivateVaultRepository
import com.malekplay.vault.PrivateVaultSession
import com.malekplay.vault.PrivateVaultTreeSupport
import com.malekplay.vault.VaultArchiveEntry
import com.malekplay.vault.VaultArchiveListingItem
import com.malekplay.vault.VaultArchiveSelectionManager
import com.malekplay.vault.VaultArchiveSort
import com.malekplay.vault.VaultInsufficientStorageException
import com.malekplay.vault.filterAndSortArchiveItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultEncryptedFolderBrowserModal(
    archiveEntry: PrivateVaultEntry,
    allVaultEntries: List<PrivateVaultEntry>,
    repository: PrivateVaultRepository,
    treeSupport: PrivateVaultTreeSupport,
    archiveBrowser: PrivateVaultArchiveBrowser,
    session: PrivateVaultSession,
    onTouchActivity: () -> Unit,
    onSuppressNextBackgroundLock: (Boolean) -> Unit,
    onPlaybackActiveChanged: (Boolean) -> Unit,
    onVaultEntryUpdated: () -> Unit,
    onMediaRestored: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentArchiveEntry by remember(archiveEntry.id) { mutableStateOf(archiveEntry) }
    val allEntries = remember(archiveEntry.id) { mutableStateListOf<VaultArchiveEntry>() }
    val pathSegments = remember(archiveEntry.id) { mutableStateListOf<String>() }
    val selectionManager = remember(archiveEntry.id) { VaultArchiveSelectionManager() }
    var selectionVersion by remember { mutableIntStateOf(0) }

    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var progressCurrent by remember { mutableIntStateOf(0) }
    var progressTotal by remember { mutableIntStateOf(0) }
    var progressLabel by remember { mutableStateOf<String?>(null) }
    var activeCancelFlag by remember { mutableStateOf<AtomicBoolean?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var includeSubfolders by remember { mutableStateOf(false) }
    var sortBy by remember { mutableStateOf(VaultArchiveSort.NAME) }
    var ascending by remember { mutableStateOf(true) }

    var viewingItem by remember { mutableStateOf<VaultArchiveListingItem?>(null) }
    var infoItem by remember { mutableStateOf<VaultArchiveListingItem?>(null) }
    var pendingDeleteItems by remember { mutableStateOf<List<VaultArchiveListingItem>?>(null) }

    val currentPath = pathSegments.joinToString("/")

    fun reloadArchiveEntries() {
        onTouchActivity()
        scope.launch {
            loading = true
            errorMessage = null
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    archiveBrowser.listAllEntries(currentArchiveEntry, session)
                }
            }
            outcome.onSuccess { loaded ->
                allEntries.clear()
                allEntries.addAll(loaded)
                val validPaths = loaded.map { it.path }.toSet()
                selectionManager.retainPaths(validPaths)
                selectionVersion++
                // If current subfolder no longer exists after deletion, walk back up safely
                while (pathSegments.isNotEmpty()) {
                    val candidate = pathSegments.joinToString("/")
                    val stillHasContent = loaded.any { it.path == candidate || it.path.startsWith("$candidate/") }
                    if (stillHasContent) break
                    pathSegments.removeAt(pathSegments.lastIndex)
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                errorMessage = err.localizedMessage ?: context.getString(R.string.vault_open_failed)
            }
            loading = false
        }
    }

    LaunchedEffect(currentArchiveEntry.id) {
        reloadArchiveEntries()
    }

    fun executeArchiveExtract(
        items: List<VaultArchiveListingItem>,
        destinationTree: Uri?,
        useOriginalFolder: Boolean,
        removeFromArchive: Boolean
    ) {
        if (items.isEmpty() || busy) return
        onTouchActivity()
        val cancelFlag = AtomicBoolean(false)
        activeCancelFlag = cancelFlag
        scope.launch {
            busy = true
            statusMessage = null
            errorMessage = null
            progressCurrent = 0
            progressTotal = items.size
            progressLabel = context.getString(R.string.vault_archive_extracting)
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    archiveBrowser.extractArchiveSelection(
                        archiveEntry = currentArchiveEntry,
                        selectedItems = items,
                        session = session,
                        destinationTree = destinationTree,
                        useOriginalFolder = useOriginalFolder,
                        removeFromArchiveAfterExtract = removeFromArchive,
                        isCancelled = { cancelFlag.get() },
                        onItemProgress = { cur, tot, name ->
                            progressCurrent = cur
                            progressTotal = tot
                            progressLabel = name
                        }
                    )
                }
            }
            activeCancelFlag = null
            progressCurrent = 0
            progressTotal = 0
            progressLabel = null
            busy = false

            outcome.onSuccess { summary ->
                if (summary.succeededCount > 0) {
                    onMediaRestored()
                }
                if (removeFromArchive && summary.removedFromVaultCount > 0) {
                    selectionManager.clear()
                    selectionVersion++
                    val refreshed = withContext(Dispatchers.IO) { repository.list(session) }
                        .firstOrNull { it.id == currentArchiveEntry.id }
                    if (refreshed != null) currentArchiveEntry = refreshed
                    onVaultEntryUpdated()
                    reloadArchiveEntries()
                }
                statusMessage = when {
                    cancelFlag.get() && summary.succeededCount == 0 ->
                        context.getString(R.string.vault_archive_cancelled)
                    summary.extractedButVaultDeleteFailedCount > 0 ->
                        context.getString(
                            R.string.vault_take_out_delete_failed,
                            summary.extractedButVaultDeleteFailedCount
                        )
                    removeFromArchive && summary.failedCount == 0 ->
                        context.getString(R.string.vault_take_out_result_success, summary.succeededCount)
                    removeFromArchive ->
                        context.getString(
                            R.string.vault_take_out_result_partial,
                            summary.succeededCount,
                            summary.failedCount
                        )
                    summary.failedCount == 0 ->
                        context.getString(R.string.vault_export_copy_result_success, summary.succeededCount)
                    else ->
                        context.getString(
                            R.string.vault_export_copy_result_partial,
                            summary.succeededCount,
                            summary.failedCount
                        )
                }
            }.onFailure { err ->
                if (err is CancellationException) throw err
                errorMessage = if (err is VaultInsufficientStorageException) {
                    context.getString(R.string.vault_error_insufficient_storage)
                } else {
                    err.localizedMessage ?: context.getString(R.string.vault_single_export_failed)
                }
            }
        }
    }

    fun executeArchiveDelete(items: List<VaultArchiveListingItem>) {
        if (items.isEmpty() || busy) return
        onTouchActivity()
        val cancelFlag = AtomicBoolean(false)
        activeCancelFlag = cancelFlag
        scope.launch {
            busy = true
            statusMessage = null
            errorMessage = null
            progressCurrent = 0
            progressTotal = 1
            progressLabel = context.getString(R.string.vault_archive_updating)
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    archiveBrowser.deleteFromArchive(
                        archiveEntry = currentArchiveEntry,
                        pathsToDelete = items.map { it.path }.toSet(),
                        session = session,
                        isCancelled = { cancelFlag.get() },
                        onProgress = { cur, tot, name ->
                            progressCurrent = cur
                            progressTotal = tot
                            progressLabel = name
                        }
                    )
                }
            }
            activeCancelFlag = null
            progressCurrent = 0
            progressTotal = 0
            progressLabel = null
            busy = false

            outcome.onSuccess { mod ->
                currentArchiveEntry = mod.updatedVaultEntry
                selectionManager.clear()
                selectionVersion++
                onVaultEntryUpdated()
                reloadArchiveEntries()
                statusMessage = context.getString(R.string.vault_archive_delete_success, items.size)
            }.onFailure { err ->
                if (err is CancellationException) throw err
                errorMessage = when {
                    cancelFlag.get() -> context.getString(R.string.vault_archive_cancelled)
                    err is VaultInsufficientStorageException -> context.getString(R.string.vault_error_insufficient_storage)
                    else -> context.getString(
                        R.string.vault_archive_delete_failed,
                        err.localizedMessage.orEmpty()
                    )
                }
            }
        }
    }

    fun requestArchiveExtract(items: List<VaultArchiveListingItem>) {
        onTouchActivity()
        if (busy || items.isEmpty()) return
        if (treeSupport.canRestoreToOriginalFolder(currentArchiveEntry)) {
            executeArchiveExtract(
                items = items,
                destinationTree = null,
                useOriginalFolder = true,
                removeFromArchive = true
            )
        } else {
            errorMessage = context.getString(R.string.vault_take_out_original_failed)
        }
    }

    val listedItems = remember(allEntries.toList(), currentPath, searchQuery, includeSubfolders, sortBy, ascending) {
        val raw = if (includeSubfolders && searchQuery.isNotBlank()) {
            archiveBrowser.searchEntries(allEntries.toList(), currentPath, searchQuery, includeSubfolders = true)
        } else if (includeSubfolders) {
            archiveBrowser.searchEntries(allEntries.toList(), currentPath, "", includeSubfolders = true)
        } else {
            archiveBrowser.listDirectory(allEntries.toList(), currentPath)
        }
        filterAndSortArchiveItems(raw, searchQuery, sortBy, ascending)
    }

    val selectedSnapshot = remember(selectionVersion) { selectionManager.selectedItems() }
    val selectedCount = selectedSnapshot.size
    val selectedTotalBytes = remember(selectedSnapshot) { selectionManager.totalBytes() }
    val allVisibleSelected = remember(listedItems, selectionVersion) {
        selectionManager.areAllSelected(listedItems)
    }

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(enabled = !busy) {
            when {
                viewingItem != null -> viewingItem = null
                pathSegments.isNotEmpty() -> pathSegments.removeAt(pathSegments.lastIndex)
                else -> onDismiss()
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
                                if (pathSegments.isNotEmpty()) {
                                    pathSegments.removeAt(pathSegments.lastIndex)
                                } else {
                                    onDismiss()
                                }
                            },
                            enabled = !busy
                        ) {
                            Icon(
                                imageVector = if (pathSegments.isNotEmpty()) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close,
                                contentDescription = stringResource(R.string.common_close),
                                tint = Color.White
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = pathSegments.lastOrNull() ?: currentArchiveEntry.displayName,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(
                                    R.string.vault_encrypted_folder_subtitle,
                                    formatVaultBytes(context, currentArchiveEntry.sizeBytes)
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFA8A3BC)
                            )
                        }
                    },
                    actions = {
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
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        requestArchiveExtract(selectedSnapshot)
                                    },
                                    enabled = !busy,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFA855F7),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.vault_take_out), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(
                                    onClick = {
                                        onTouchActivity()
                                        pendingDeleteItems = selectedSnapshot
                                    },
                                    enabled = !busy
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.vault_batch_delete_button),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Breadcrumbs
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = pathSegments.isEmpty(),
                        onClick = {
                            onTouchActivity()
                            pathSegments.clear()
                        },
                        label = { Text(currentArchiveEntry.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    )
                    pathSegments.forEachIndexed { idx, segment ->
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = Color(0xFF7D7794),
                            modifier = Modifier.size(18.dp)
                        )
                        FilterChip(
                            selected = idx == pathSegments.lastIndex,
                            onClick = {
                                onTouchActivity()
                                while (pathSegments.size > idx + 1) {
                                    pathSegments.removeAt(pathSegments.lastIndex)
                                }
                            },
                            label = { Text(segment, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }

                // Search + Select All
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
                        placeholder = { Text(stringResource(R.string.vault_archive_search_hint)) },
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
                            if (allVisibleSelected) {
                                selectionManager.deselectAll(listedItems)
                            } else {
                                selectionManager.selectAll(listedItems)
                            }
                            selectionVersion++
                        },
                        enabled = listedItems.isNotEmpty() && !busy
                    ) {
                        Icon(
                            imageVector = if (allVisibleSelected) Icons.Filled.CheckBox else Icons.Filled.SelectAll,
                            contentDescription = stringResource(
                                if (allVisibleSelected) R.string.vault_deselect_all else R.string.vault_select_all
                            ),
                            tint = if (allVisibleSelected) Color(0xFFA855F7) else Color.White
                        )
                    }
                }

                // Sort & Recursive Search Chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = includeSubfolders,
                        onClick = {
                            onTouchActivity()
                            includeSubfolders = !includeSubfolders
                        },
                        label = { Text(stringResource(R.string.vault_archive_include_subfolders)) }
                    )
                    VaultArchiveSort.entries.forEach { option ->
                        val labelRes = when (option) {
                            VaultArchiveSort.NAME -> R.string.vault_sort_name
                            VaultArchiveSort.SIZE -> R.string.vault_sort_size
                            VaultArchiveSort.TYPE -> R.string.vault_archive_sort_type
                            VaultArchiveSort.DATE -> R.string.vault_browser_sort_date
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
                }

                if (loading) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFA855F7))
                        Text(
                            text = stringResource(R.string.vault_archive_loading),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFA8A3BC)
                        )
                    }
                }

                if (busy) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val frac = if (progressTotal > 0) (progressCurrent.toFloat() / progressTotal.toFloat()).coerceIn(0f, 1f) else null
                        if (frac != null) {
                            LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth(), color = Color(0xFFA855F7))
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Color(0xFFA855F7))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val label = buildString {
                                if (progressTotal > 0) append("$progressCurrent / $progressTotal ")
                                progressLabel?.let { append(it) }
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFD8B4FE),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            activeCancelFlag?.let { flag ->
                                TextButton(onClick = { flag.set(true) }) {
                                    Text(stringResource(R.string.common_cancel))
                                }
                            }
                        }
                    }
                }

                statusMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFFD8B4FE))
                }
                errorMessage?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (!loading && listedItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (allEntries.isEmpty()) {
                                stringResource(R.string.vault_archive_empty_folder)
                            } else {
                                stringResource(R.string.vault_archive_no_matches)
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
                        items(listedItems, key = { it.path }) { item ->
                            val isSelected = remember(selectionVersion, item.path) {
                                selectionManager.contains(item.path)
                            }
                            var rowMenuOpen by remember(item.path) { mutableStateOf(false) }

                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) {
                                        onTouchActivity()
                                        if (item.isDirectory) {
                                            pathSegments.clear()
                                            pathSegments.addAll(item.path.split('/').filter { it.isNotEmpty() })
                                        } else if (selectedCount > 0) {
                                            selectionManager.toggle(item)
                                            selectionVersion++
                                        } else {
                                            viewingItem = item
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            onTouchActivity()
                                            selectionManager.setSelected(item, checked)
                                            selectionVersion++
                                        },
                                        enabled = !busy
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF1F1E2D)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = when {
                                                item.isDirectory -> Icons.Filled.Folder
                                                item.mimeType.startsWith("video/") -> Icons.Filled.VideoFile
                                                item.mimeType.startsWith("audio/") -> Icons.Filled.Audiotrack
                                                item.mimeType.startsWith("image/") -> Icons.Filled.Image
                                                item.mimeType.startsWith("text/") || item.mimeType == "application/pdf" -> Icons.Filled.Description
                                                else -> Icons.Filled.InsertDriveFile
                                            },
                                            contentDescription = null,
                                            tint = Color(0xFFA855F7),
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val subtitle = if (item.isDirectory) {
                                            stringResource(R.string.vault_browser_folder_items_count, item.childCount)
                                        } else {
                                            val datePart = if (item.lastModifiedMs > 0L) {
                                                " · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.lastModifiedMs))}"
                                            } else ""
                                            val pathPrefix = if (includeSubfolders && item.parentPath.isNotEmpty()) {
                                                "${item.parentPath} · "
                                            } else ""
                                            "$pathPrefix${formatVaultBytes(context, item.sizeBytes)}$datePart"
                                        }
                                        Text(
                                            text = subtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Box {
                                        IconButton(
                                            onClick = {
                                                onTouchActivity()
                                                rowMenuOpen = true
                                            },
                                            enabled = !busy
                                        ) {
                                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                                        }
                                        DropdownMenu(
                                            expanded = rowMenuOpen,
                                            onDismissRequest = { rowMenuOpen = false }
                                        ) {
                                            DropdownMenuItem(
                                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = null) },
                                                text = { Text(stringResource(R.string.vault_take_out)) },
                                                onClick = {
                                                    rowMenuOpen = false
                                                    requestArchiveExtract(listOf(item))
                                                }
                                            )
                                            DropdownMenuItem(
                                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                                text = { Text(stringResource(R.string.vault_file_info)) },
                                                onClick = {
                                                    rowMenuOpen = false
                                                    infoItem = item
                                                }
                                            )
                                            DropdownMenuItem(
                                                leadingIcon = {
                                                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                                },
                                                text = {
                                                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                                                },
                                                onClick = {
                                                    rowMenuOpen = false
                                                    pendingDeleteItems = listOf(item)
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
        }
    }

    // In-vault viewer for archive item (O(1) memory streaming for STORED media, in-memory image/text)
    viewingItem?.let { item ->
        VaultMediaViewerDialog(
            entry = currentArchiveEntry,
            repository = repository,
            session = session,
            entries = allVaultEntries,
            archiveBrowser = archiveBrowser,
            archiveItem = item,
            onPlaybackActiveChanged = onPlaybackActiveChanged,
            onDismiss = {
                onPlaybackActiveChanged(false)
                viewingItem = null
                onTouchActivity()
            }
        )
    }

    pendingDeleteItems?.let { items ->
        AlertDialog(
            onDismissRequest = { pendingDeleteItems = null },
            title = { Text(stringResource(R.string.vault_archive_delete_title)) },
            text = { Text(stringResource(R.string.vault_archive_delete_message, items.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteItems = null
                        executeArchiveDelete(items)
                    }
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteItems = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    infoItem?.let { item ->
        val dateText = remember(item.lastModifiedMs) {
            if (item.lastModifiedMs > 0L) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.lastModifiedMs))
            } else "-"
        }
        AlertDialog(
            onDismissRequest = { infoItem = null },
            title = { Text(stringResource(R.string.vault_file_info)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.vault_info_name, item.name))
                    Text(stringResource(R.string.vault_archive_info_path, item.path))
                    Text(stringResource(R.string.vault_info_type, item.mimeType))
                    Text(
                        stringResource(
                            R.string.vault_info_size,
                            if (item.isDirectory) stringResource(R.string.vault_browser_folder_items_count, item.childCount)
                            else formatVaultBytes(context, item.sizeBytes)
                        )
                    )
                    Text(stringResource(R.string.vault_info_added_at, dateText))
                    Text(
                        stringResource(R.string.vault_info_encryption),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { infoItem = null }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }
}
