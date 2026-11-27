package com.malekplay.ui.vault

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.derivedStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.malekplay.R
import com.malekplay.domain.model.LibraryItem
import com.malekplay.ui.library.LibraryViewModel
import com.malekplay.vault.PrivateVaultDeviceSource
import com.malekplay.vault.PrivateVaultTreeSupport
import com.malekplay.vault.VaultBrowserItem
import com.malekplay.vault.VaultBrowserSessionSnapshot
import com.malekplay.vault.VaultBrowserSort
import com.malekplay.vault.VaultBrowserStateHolder
import com.malekplay.vault.VaultBrowserStep
import com.malekplay.vault.VaultDeviceFolder
import com.malekplay.vault.VaultDeviceItem
import com.malekplay.vault.VaultDeviceSourceKind
import com.malekplay.vault.VaultImportBatchResult
import com.malekplay.vault.buildFolderContentsForPath
import com.malekplay.vault.combineLibraryAndExtraDeviceFolders
import com.malekplay.vault.extractExtension
import com.malekplay.vault.filterAndSortDeviceFolders
import com.malekplay.vault.filterAndSortDeviceItems
import com.malekplay.vault.normalizeRelativeFolderPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun VaultInternalBrowserModal(
    libraryViewModel: LibraryViewModel,
    treeSupport: PrivateVaultTreeSupport,
    rootTreeUri: Uri?,
    busy: Boolean,
    importProgress: Int = 0,
    importTotal: Int = 0,
    progressDetail: String? = null,
    externalRefreshKey: Int = 0,
    onTouchActivity: () -> Unit,
    onSuppressNextBackgroundLock: (Boolean) -> Unit,
    onPickSafRootFolder: () -> Unit,
    onOpenSystemPickerFallback: () -> Unit,
    onSaveDeviceSelection: suspend (List<VaultDeviceItem>, moveSource: Boolean) -> VaultImportBatchResult,
    onImportSafSubfolder: (treeUri: Uri, folderDocId: String, parentDocId: String?, folderName: String, displayPath: String, move: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceSource = remember(context) { PrivateVaultDeviceSource(context) }

    val stateHolder = remember { VaultBrowserStateHolder() }
    var snapshot by remember { mutableStateOf(stateHolder.openFolderList()) }
    fun syncState(next: VaultBrowserSessionSnapshot) {
        snapshot = next
    }

    val libraryFolders by libraryViewModel.folders.collectAsStateWithLifecycle()
    val libraryRefreshing by libraryViewModel.isRefreshing.collectAsStateWithLifecycle()
    val extraDeviceItems = remember { mutableStateListOf<VaultDeviceItem>() }
    var hasMediaPermission by remember { mutableStateOf(deviceSource.hasMediaPermissions()) }
    var supplementalReloadTick by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onSuppressNextBackgroundLock(false)
        onTouchActivity()
        hasMediaPermission = deviceSource.hasMediaPermissions()
        libraryViewModel.refresh()
        supplementalReloadTick++
    }

    // Load accessible images/downloads/files on IO thread without blocking main thread
    LaunchedEffect(hasMediaPermission, supplementalReloadTick, externalRefreshKey) {
        val loaded = withContext(Dispatchers.IO) {
            deviceSource.querySupplementalItems()
        }
        extraDeviceItems.clear()
        extraDeviceItems.addAll(loaded)
    }

    val safFolderLabel = stringResource(R.string.vault_browser_saf_saved_folder)
    val safRootFolder = remember(rootTreeUri, safFolderLabel) {
        rootTreeUri?.let { uri ->
            val label = runCatching { treeSupport.queryTreeRootLabel(uri) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: safFolderLabel
            VaultDeviceFolder(
                path = "saf:${uri}",
                name = label,
                itemCount = 0,
                sourceKind = VaultDeviceSourceKind.SAF,
                safTreeUri = uri,
                safDocumentId = null
            )
        }
    }

    val combinedFolders = remember(libraryFolders, extraDeviceItems.toList(), safRootFolder) {
        combineLibraryAndExtraDeviceFolders(
            libraryFolders = libraryFolders,
            extraItems = extraDeviceItems.toList(),
            safRootFolder = safRootFolder
        )
    }

    var folderQuery by remember { mutableStateOf("") }
    var itemQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(VaultBrowserSort.NAME) }
    var ascending by remember { mutableStateOf(true) }

    val safItems = remember { mutableStateListOf<VaultDeviceItem>() }
    val safChildCounts = remember { mutableStateMapOf<String, Int?>() }
    var safLoading by remember { mutableStateOf(false) }
    var safLoadError by remember { mutableStateOf<String?>(null) }
    var pendingSafSubfolderImport by remember { mutableStateOf<VaultDeviceItem?>(null) }

    val thumbCache = remember {
        object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount.coerceAtLeast(1)
        }
    }
    DisposableEffect(Unit) {
        onDispose { thumbCache.evictAll() }
    }

    val activeStep = snapshot.step
    val activeFolderStep = activeStep as? VaultBrowserStep.FolderContents

    // Observe current folder items from LibraryViewModel when browsing a MediaStore folder
    val libraryFolderItems by remember(activeFolderStep?.currentPath, activeFolderStep?.sourceKind) {
        if (activeFolderStep != null && activeFolderStep.sourceKind == VaultDeviceSourceKind.MEDIA_STORE) {
            libraryViewModel.folderContents(activeFolderStep.currentPath)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList<LibraryItem>())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    fun loadSafDirectory(treeUri: Uri, docId: String?, folderDisplayPath: String) {
        onTouchActivity()
        scope.launch {
            safLoading = true
            safLoadError = null
            runCatching {
                withContext(Dispatchers.IO) {
                    treeSupport.listBrowserChildren(treeUri, docId).map { item ->
                        item.toDeviceItem(
                            treeUri = treeUri,
                            parentDocumentId = docId,
                            displayFolderPath = folderDisplayPath
                        )
                    }
                }
            }.onSuccess { loaded ->
                safItems.clear()
                safItems.addAll(loaded)
            }.onFailure { err ->
                safItems.clear()
                safLoadError = err.localizedMessage
            }
            safLoading = false
        }
    }

    val safRootLabel = stringResource(R.string.vault_root_crumb)

    LaunchedEffect(
        activeFolderStep?.sourceKind,
        activeFolderStep?.safTreeUri,
        activeFolderStep?.currentSafDocId
    ) {
        if (activeFolderStep != null &&
            activeFolderStep.sourceKind == VaultDeviceSourceKind.SAF &&
            activeFolderStep.safTreeUri != null
        ) {
            val displayPath = buildString {
                append(safRootLabel)
                activeFolderStep.stack.drop(1).forEach { append(" / ").append(it.second) }
            }
            loadSafDirectory(
                treeUri = activeFolderStep.safTreeUri,
                docId = activeFolderStep.currentSafDocId,
                folderDisplayPath = displayPath
            )
        }
    }

    // Current folder items (either Library+MediaStore or SAF)
    val rawFolderItems = remember(
        activeFolderStep,
        libraryFolderItems,
        extraDeviceItems.toList(),
        combinedFolders,
        safItems.toList()
    ) {
        when {
            activeFolderStep == null -> emptyList()
            activeFolderStep.sourceKind == VaultDeviceSourceKind.SAF -> safItems.toList()
            else -> {
                val targetNorm = normalizeRelativeFolderPath(activeFolderStep.currentPath)
                val extrasForFolder = extraDeviceItems.filter {
                    normalizeRelativeFolderPath(it.folderPath) == targetNorm
                }
                buildFolderContentsForPath(
                    folderPath = targetNorm,
                    libraryFolderItems = libraryFolderItems,
                    extraFolderItems = extrasForFolder,
                    allFolders = combinedFolders
                )
            }
        }
    }

    val visibleFolders = remember(combinedFolders, folderQuery) {
        filterAndSortDeviceFolders(combinedFolders, folderQuery)
    }

    val visibleFolderItems = remember(rawFolderItems, itemQuery, sortBy, ascending) {
        filterAndSortDeviceItems(rawFolderItems, itemQuery, sortBy, ascending)
    }

    val pagedFolderItems = remember(visibleFolderItems, snapshot.visibleItemLimit) {
        if (visibleFolderItems.size <= snapshot.visibleItemLimit) {
            visibleFolderItems
        } else {
            visibleFolderItems.take(snapshot.visibleItemLimit)
        }
    }
    val remainingPagedCount = (visibleFolderItems.size - pagedFolderItems.size).coerceAtLeast(0)

    val listState = rememberLazyListState()
    val reachedEnd by remember(listState, remainingPagedCount) {
        derivedStateOf {
            if (remainingPagedCount <= 0) false
            else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= pagedFolderItems.lastIndex - 6
            }
        }
    }
    LaunchedEffect(reachedEnd) {
        if (reachedEnd && remainingPagedCount > 0) {
            syncState(stateHolder.loadMoreItems())
        }
    }

    val currentSelectableFiles = remember(visibleFolderItems) {
        visibleFolderItems.filter { !it.isDirectory }
    }
    val allCurrentSelected = remember(currentSelectableFiles, snapshot) {
        stateHolder.areAllSelectableFilesSelected(currentSelectableFiles)
    }

    val saveBusy = busy || snapshot.isSaving

    fun performSave(moveSource: Boolean) {
        onTouchActivity()
        val batch = stateHolder.tryBeginSave() ?: return
        syncState(stateHolder.snapshot)
        scope.launch {
            val result = runCatching {
                onSaveDeviceSelection(batch, moveSource)
            }.getOrNull()
            if (result != null) {
                val messageText = if (result.failedCount == 0) {
                    context.getString(R.string.vault_browser_save_result_banner, result.importedCount)
                } else {
                    context.getString(
                        R.string.vault_browser_save_partial_banner,
                        result.importedCount,
                        result.failedCount
                    )
                }
                syncState(stateHolder.finishSave(result.succeededKeys, messageText))
                if (activeFolderStep?.sourceKind == VaultDeviceSourceKind.SAF &&
                    activeFolderStep.safTreeUri != null &&
                    moveSource &&
                    result.movedCount > 0
                ) {
                    val displayPath = buildString {
                        append(safRootLabel)
                        activeFolderStep.stack.drop(1).forEach { append(" / ").append(it.second) }
                    }
                    loadSafDirectory(
                        treeUri = activeFolderStep.safTreeUri,
                        docId = activeFolderStep.currentSafDocId,
                        folderDisplayPath = displayPath
                    )
                }
                if (moveSource && result.movedCount > 0) {
                    supplementalReloadTick++
                }
            } else {
                syncState(stateHolder.finishSave(emptySet(), null))
            }
        }
    }

    fun handleCloseX() {
        onTouchActivity()
        if (saveBusy) return
        val next = stateHolder.onCloseButtonPressed()
        syncState(next)
        if (!next.isOpen) {
            onDismiss()
        } else {
            itemQuery = ""
        }
    }

    fun handleSystemBack() {
        onTouchActivity()
        if (saveBusy) return
        val next = stateHolder.onSystemBackPressed()
        syncState(next)
        if (!next.isOpen) {
            onDismiss()
        } else {
            itemQuery = ""
        }
    }

    Dialog(
        onDismissRequest = { if (!saveBusy) handleCloseX() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        BackHandler(enabled = !saveBusy) {
            handleSystemBack()
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
                        if (activeFolderStep != null) {
                            IconButton(
                                onClick = { handleSystemBack() },
                                enabled = !saveBusy
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.cd_back),
                                    tint = Color.White
                                )
                            }
                        }
                    },
                    title = {
                        if (activeFolderStep != null) {
                            Column {
                                Text(
                                    text = activeFolderStep.currentName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(
                                        R.string.vault_browser_current_folder,
                                        activeFolderStep.currentPath
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFA8A3BC)
                                )
                            }
                        } else {
                            Column {
                                Text(
                                    text = stringResource(R.string.vault_browser_folders_title),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.vault_browser_folders_subtitle),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFA8A3BC)
                                )
                            }
                        }
                    },
                    actions = {
                        if (activeFolderStep == null) {
                            IconButton(
                                onClick = {
                                    onTouchActivity()
                                    libraryViewModel.refresh()
                                    supplementalReloadTick++
                                },
                                enabled = !saveBusy && !libraryRefreshing
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = stringResource(R.string.vault_browser_refresh_folders),
                                    tint = Color(0xFFA855F7)
                                )
                            }
                        }
                        IconButton(
                            onClick = { handleCloseX() },
                            enabled = !saveBusy
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
                if (snapshot.pendingCount > 0 || snapshot.isSaving) {
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
                                        R.string.vault_browser_pending_selected,
                                        snapshot.pendingCount,
                                        formatVaultBytes(context, snapshot.pendingTotalBytes)
                                    ),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color(0xFFD8B4FE),
                                    fontWeight = FontWeight.SemiBold
                                )
                                TextButton(
                                    onClick = {
                                        onTouchActivity()
                                        stateHolder.clearPendingSelection()
                                        syncState(stateHolder.snapshot)
                                    },
                                    enabled = !saveBusy
                                ) {
                                    Text(stringResource(R.string.vault_deselect_all))
                                }
                            }
                            if (saveBusy) {
                                if (importTotal > 0) {
                                    LinearProgressIndicator(
                                        progress = { (importProgress.toFloat() / importTotal.toFloat()).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFFA855F7)
                                    )
                                    val statusText = buildString {
                                        append("$importProgress / $importTotal")
                                        progressDetail?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                                    }
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFD8B4FE),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth(),
                                        color = Color(0xFFA855F7)
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { performSave(moveSource = false) },
                                    enabled = !saveBusy && snapshot.pendingCount > 0,
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
                                    onClick = { performSave(moveSource = true) },
                                    enabled = !saveBusy && snapshot.pendingCount > 0,
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFA855F7),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.vault_browser_save_to_vault, snapshot.pendingCount),
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                snapshot.lastSaveResultMessage?.let { resultBanner ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF1F1633),
                        border = BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.6f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFFA855F7),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = resultBanner,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE9D5FF),
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = {
                                    stateHolder.clearSaveResultMessage()
                                    syncState(stateHolder.snapshot)
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.common_close),
                                    tint = Color(0xFFD8B4FE),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                if (activeFolderStep == null) {
                    // PHASE 2: Folder list inside MalekPlayer
                    if (!hasMediaPermission) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = Color(0xFF161622),
                            border = BorderStroke(1.dp, Color(0xFF33274D)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.vault_browser_permission_banner),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFD0CADF)
                                )
                                Button(
                                    onClick = {
                                        onTouchActivity()
                                        onSuppressNextBackgroundLock(true)
                                        permissionLauncher.launch(deviceSource.permissionsForBrowsePrompt())
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFA855F7),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Text(stringResource(R.string.vault_browser_grant_media_access))
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = folderQuery,
                        onValueChange = {
                            onTouchActivity()
                            folderQuery = it
                        },
                        placeholder = { Text(stringResource(R.string.vault_browser_search_hint)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        trailingIcon = {
                            if (folderQuery.isNotEmpty()) {
                                IconButton(onClick = { folderQuery = "" }) {
                                    Icon(Icons.Filled.Close, contentDescription = null)
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (libraryRefreshing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFA855F7)
                        )
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = if (snapshot.pendingCount > 0) 110.dp else 16.dp)
                    ) {
                        items(visibleFolders, key = { it.path }) { folder ->
                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !saveBusy) {
                                        onTouchActivity()
                                        itemQuery = ""
                                        syncState(stateHolder.enterFolder(folder))
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
                                            imageVector = if (folder.sourceKind == VaultDeviceSourceKind.SAF) {
                                                Icons.Filled.FolderOpen
                                            } else {
                                                Icons.Filled.Folder
                                            },
                                            contentDescription = null,
                                            tint = Color(0xFFA855F7),
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = folder.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val countSummary = buildString {
                                            if (folder.sourceKind == VaultDeviceSourceKind.SAF) {
                                                append(stringResource(R.string.vault_browser_saf_saved_folder))
                                            } else {
                                                append(stringResource(R.string.vault_browser_folder_items_count, folder.itemCount))
                                                if (folder.subfolderCount > 0) {
                                                    append(" · ")
                                                    append(stringResource(R.string.vault_browser_subfolder_count, folder.subfolderCount))
                                                }
                                                if (folder.totalBytes > 0L) {
                                                    append(" · ")
                                                    append(formatVaultBytes(context, folder.totalBytes))
                                                }
                                            }
                                        }
                                        Text(
                                            text = countSummary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (folder.sourceKind == VaultDeviceSourceKind.MEDIA_STORE && folder.path != folder.name) {
                                            Text(
                                                text = folder.path,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF8C86A3),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.Filled.ChevronRight,
                                        contentDescription = null,
                                        tint = Color(0xFF9C96B4)
                                    )
                                }
                            }
                        }

                        // Complementary SAF & system picker fallback options (never auto-launched on +)
                        item(key = "saf_complementary_footer") {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF151520),
                                border = BorderStroke(1.dp, Color(0xFF28253A)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 6.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            onTouchActivity()
                                            onPickSafRootFolder()
                                        },
                                        enabled = !saveBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(
                                            Icons.Filled.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.vault_browser_saf_folder_option))
                                    }
                                    TextButton(
                                        onClick = {
                                            onTouchActivity()
                                            onOpenSystemPickerFallback()
                                        },
                                        enabled = !saveBusy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.vault_browser_system_picker_fallback))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // PHASE 3: Folder contents (subfolders + files)
                    // Breadcrumb trail
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = false,
                            onClick = {
                                onTouchActivity()
                                itemQuery = ""
                                syncState(stateHolder.navigateToBreadcrumb(-1))
                            },
                            label = { Text(stringResource(R.string.vault_browser_folders_title)) }
                        )
                        activeFolderStep.stack.forEachIndexed { index, pair ->
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = null,
                                tint = Color(0xFF7D7794),
                                modifier = Modifier.size(18.dp)
                            )
                            FilterChip(
                                selected = index == activeFolderStep.stack.lastIndex,
                                onClick = {
                                    onTouchActivity()
                                    if (index < activeFolderStep.stack.lastIndex) {
                                        itemQuery = ""
                                        syncState(stateHolder.navigateToBreadcrumb(index))
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
                            value = itemQuery,
                            onValueChange = {
                                onTouchActivity()
                                itemQuery = it
                            },
                            placeholder = { Text(stringResource(R.string.vault_browser_search_hint)) },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            trailingIcon = {
                                if (itemQuery.isNotEmpty()) {
                                    IconButton(onClick = { itemQuery = "" }) {
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
                                if (allCurrentSelected) {
                                    stateHolder.deselectAll(currentSelectableFiles)
                                } else {
                                    stateHolder.selectAll(currentSelectableFiles)
                                }
                                syncState(stateHolder.snapshot)
                            },
                            enabled = currentSelectableFiles.isNotEmpty() && !saveBusy
                        ) {
                            Icon(
                                imageVector = if (allCurrentSelected) Icons.Filled.CheckBox else Icons.Filled.SelectAll,
                                contentDescription = stringResource(
                                    if (allCurrentSelected) R.string.vault_deselect_all else R.string.vault_select_all
                                ),
                                tint = if (allCurrentSelected) Color(0xFFA855F7) else Color.White
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
                    }

                    if (safLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFFA855F7)
                        )
                    }
                    safLoadError?.let { err ->
                        Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    if (!safLoading && pagedFolderItems.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (rawFolderItems.isEmpty()) {
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
                            state = listState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(bottom = if (snapshot.pendingCount > 0) 110.dp else 16.dp)
                        ) {
                            items(pagedFolderItems, key = { it.selectionKey }) { item ->
                                if (item.isDirectory) {
                                    if (item.sourceKind == VaultDeviceSourceKind.SAF && item.safTreeUri != null && item.safDocumentId != null) {
                                        LaunchedEffect(item.safDocumentId, item.safTreeUri) {
                                            if (!safChildCounts.containsKey(item.safDocumentId)) {
                                                val computed = withContext(Dispatchers.IO) {
                                                    treeSupport.countDirectChildren(item.safTreeUri, item.safDocumentId)
                                                }
                                                safChildCounts[item.safDocumentId] = computed
                                            }
                                        }
                                    }
                                    val subCount = if (item.sourceKind == VaultDeviceSourceKind.SAF) {
                                        safChildCounts[item.safDocumentId]
                                    } else {
                                        item.subfolderCount
                                    }

                                    ElevatedCard(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !saveBusy) {
                                                onTouchActivity()
                                                itemQuery = ""
                                                val targetIdOrPath = item.safDocumentId ?: item.folderPath
                                                syncState(stateHolder.enterSubfolder(targetIdOrPath, item.displayName))
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
                                                    text = if (subCount != null) {
                                                        stringResource(R.string.vault_browser_folder_items_count, subCount)
                                                    } else {
                                                        stringResource(R.string.vault_browser_folder_label)
                                                    },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            if (item.sourceKind == VaultDeviceSourceKind.SAF) {
                                                IconButton(
                                                    onClick = {
                                                        onTouchActivity()
                                                        pendingSafSubfolderImport = item
                                                    },
                                                    enabled = !saveBusy
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Filled.CreateNewFolder,
                                                        contentDescription = stringResource(R.string.vault_browser_import_folder_btn),
                                                        tint = Color(0xFFA855F7)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    val isSaved = item.selectionKey in snapshot.savedKeys
                                    val isSelected = item.selectionKey in snapshot.pendingSelectedByKey
                                    val borderStroke = when {
                                        isSelected -> BorderStroke(1.5.dp, Color(0xFFA855F7))
                                        isSaved -> BorderStroke(1.dp, Color(0xFF4ADE80).copy(alpha = 0.55f))
                                        else -> null
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isSelected) Color(0xFF211638) else Color(0xFF161622),
                                        border = borderStroke,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !saveBusy && !isSaved) {
                                                onTouchActivity()
                                                stateHolder.toggleSelection(item)
                                                syncState(stateHolder.snapshot)
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (isSaved) {
                                                Icon(
                                                    imageVector = Icons.Filled.CheckCircle,
                                                    contentDescription = stringResource(R.string.vault_browser_already_saved_badge),
                                                    tint = Color(0xFF4ADE80),
                                                    modifier = Modifier
                                                        .padding(horizontal = 10.dp)
                                                        .size(22.dp)
                                                )
                                            } else {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = { checked ->
                                                        onTouchActivity()
                                                        stateHolder.setSelected(item, checked)
                                                        syncState(stateHolder.snapshot)
                                                    },
                                                    enabled = !saveBusy
                                                )
                                            }
                                            DeviceItemThumbnail(
                                                item = item,
                                                deviceSource = deviceSource,
                                                cache = thumbCache
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = item.displayName,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    if (item.extension.isNotBlank()) {
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = Color(0xFFA855F7).copy(alpha = 0.2f)
                                                        ) {
                                                            Text(
                                                                text = item.extension,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = Color(0xFFD8B4FE),
                                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                            )
                                                        }
                                                    }
                                                    val dateSuffix = if (item.lastModifiedMs > 0L) {
                                                        " · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.lastModifiedMs))}"
                                                    } else ""
                                                    Text(
                                                        text = "${formatVaultBytes(context, item.sizeBytes)}$dateSuffix",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = Color(0xFFA8A3BC),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            if (isSaved) {
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = Color(0xFF4ADE80).copy(alpha = 0.16f)
                                                ) {
                                                    Text(
                                                        text = stringResource(R.string.vault_browser_already_saved_badge),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = Color(0xFF86EFAC),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (remainingPagedCount > 0) {
                                item(key = "load_more_device_items") {
                                    OutlinedButton(
                                        onClick = {
                                            onTouchActivity()
                                            syncState(stateHolder.loadMoreItems())
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(stringResource(R.string.vault_browser_load_more, remainingPagedCount))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val safFolderCandidate = pendingSafSubfolderImport
    val safTree = safFolderCandidate?.safTreeUri
    val safDocId = safFolderCandidate?.safDocumentId
    if (safFolderCandidate != null && safTree != null && safDocId != null && activeFolderStep != null) {
        val fullSubfolderPath = buildString {
            append(safRootLabel)
            activeFolderStep.stack.drop(1).forEach { append(" / ").append(it.second) }
            append(" / ").append(safFolderCandidate.displayName)
        }
        val parentDocId = activeFolderStep.currentSafDocId
        AlertDialog(
            onDismissRequest = { pendingSafSubfolderImport = null },
            title = { Text(stringResource(R.string.vault_browser_import_folder_title, safFolderCandidate.displayName)) },
            text = { Text(stringResource(R.string.vault_browser_import_folder_body)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            pendingSafSubfolderImport = null
                            onImportSafSubfolder(safTree, safDocId, parentDocId, safFolderCandidate.displayName, fullSubfolderPath, false)
                        }
                    ) {
                        Text(stringResource(R.string.vault_browser_copy_to_vault))
                    }
                    TextButton(
                        onClick = {
                            pendingSafSubfolderImport = null
                            onImportSafSubfolder(safTree, safDocId, parentDocId, safFolderCandidate.displayName, fullSubfolderPath, true)
                        }
                    ) {
                        Text(stringResource(R.string.vault_browser_move_to_vault), color = Color(0xFFA855F7))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSafSubfolderImport = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

private fun VaultBrowserItem.toDeviceItem(
    treeUri: Uri,
    parentDocumentId: String?,
    displayFolderPath: String
): VaultDeviceItem = VaultDeviceItem(
    id = documentId,
    uri = uri,
    displayName = displayName,
    extension = if (isDirectory) "" else extractExtension(displayName, mimeType),
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    lastModifiedMs = lastModified,
    folderPath = displayFolderPath,
    folderDisplayPath = displayFolderPath,
    isDirectory = isDirectory,
    sourceKind = VaultDeviceSourceKind.SAF,
    safTreeUri = treeUri,
    safParentDocumentId = parentDocumentId,
    safDocumentId = documentId
)

@Composable
private fun DeviceItemThumbnail(
    item: VaultDeviceItem,
    deviceSource: PrivateVaultDeviceSource,
    cache: LruCache<String, Bitmap>
) {
    val cacheKey = item.selectionKey
    var thumb by remember(cacheKey) { mutableStateOf(cache.get(cacheKey)) }
    val isMediaThumb = item.mimeType.startsWith("image/") || item.mimeType.startsWith("video/")
    val signal = remember(cacheKey) { CancellationSignal() }

    DisposableEffect(cacheKey, isMediaThumb) {
        onDispose { signal.cancel() }
    }

    LaunchedEffect(cacheKey) {
        if (!isMediaThumb || thumb != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            deviceSource.loadThumbnail(item.uri, item.mimeType, signal)
        }
        if (loaded != null && !signal.isCanceled) {
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

internal fun formatVaultBytes(context: Context, bytes: Long): String {
    if (bytes < 0L) return context.getString(R.string.vault_unknown_size)
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f)
        bytes < 1024L * 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f))
    }
}
