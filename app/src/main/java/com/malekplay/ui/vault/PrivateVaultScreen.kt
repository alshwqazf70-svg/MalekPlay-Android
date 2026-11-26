package com.malekplay.ui.vault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.WindowManager
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.malekplay.R
import com.malekplay.ui.library.LibraryViewModel
import com.malekplay.vault.PrivateVaultEntry
import com.malekplay.vault.PrivateVaultRepository
import com.malekplay.vault.PrivateVaultSession
import com.malekplay.vault.PrivateVaultTreeSupport
import com.malekplay.vault.SelectedVaultBrowserItem
import com.malekplay.vault.VaultEntryFilter
import com.malekplay.vault.VaultEntrySort
import com.malekplay.vault.filterAndSortVaultEntries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

private data class PendingTransferRequest(
    val entries: List<PrivateVaultEntry>,
    val removeFromVault: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateVaultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findVaultActivity() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val repository = remember(context) { PrivateVaultRepository(context) }
    val treeSupport = remember(context, repository) { PrivateVaultTreeSupport(context, repository) }
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var vaultSession by remember { mutableStateOf<PrivateVaultSession?>(null) }
    var configured by remember { mutableStateOf(repository.isConfigured()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val entries = remember { mutableStateListOf<PrivateVaultEntry>() }

    // Viewer, batch, search, filter, sort, change-password state
    var viewingEntry by remember { mutableStateOf<PrivateVaultEntry?>(null) }
    var isMediaPlaying by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(VaultEntryFilter.ALL) }
    var activeSort by remember { mutableStateOf(VaultEntrySort.NEWEST) }
    var sortAscending by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val selectedEntryIds = remember { mutableStateListOf<String>() }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }
    var infoEntry by remember { mutableStateOf<PrivateVaultEntry?>(null) }

    // Security hardening: brute-force backoff + background/idle auto-lock
    var failedAttempts by remember { mutableIntStateOf(0) }
    var lockoutUntilElapsedMs by remember { mutableLongStateOf(0L) }
    var remainingLockoutSeconds by remember { mutableIntStateOf(0) }
    var lastInteractionElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var suppressNextBackgroundLock by remember { mutableStateOf(false) }

    // SAF / transfer state
    var pendingDelete by remember { mutableStateOf<PrivateVaultEntry?>(null) }
    var transferSheetRequest by remember { mutableStateOf<PendingTransferRequest?>(null) }
    var confirmOriginalRestoreRequest by remember { mutableStateOf<PendingTransferRequest?>(null) }
    var customDestTransferRequest by remember { mutableStateOf<PendingTransferRequest?>(null) }
    var pendingSingleCreateDocEntry by remember { mutableStateOf<PrivateVaultEntry?>(null) }
    var moveFolderSelection by remember { mutableStateOf(false) }

    val vaultPrefs = remember(context) {
        context.getSharedPreferences("private_vault_browser", Context.MODE_PRIVATE)
    }
    var browserTreeUri by remember {
        mutableStateOf(vaultPrefs.getString("source_tree_uri", null)?.let(Uri::parse))
    }
    var browserOpen by remember { mutableStateOf(false) }

    var importProgress by remember { mutableIntStateOf(0) }
    var importTotal by remember { mutableIntStateOf(0) }
    var progressDetail by remember { mutableStateOf<String?>(null) }
    var byteFraction by remember { mutableFloatStateOf(0f) }
    var activeCancelFlag by remember { mutableStateOf<AtomicBoolean?>(null) }

    fun touchActivity() {
        lastInteractionElapsedMs = SystemClock.elapsedRealtime()
    }

    fun lockVault(reasonMessage: String? = null) {
        activeCancelFlag?.set(true)
        activeCancelFlag = null
        viewingEntry = null
        isMediaPlaying = false
        showChangePasswordDialog = false
        showBatchDeleteConfirm = false
        infoEntry = null
        pendingDelete = null
        transferSheetRequest = null
        confirmOriginalRestoreRequest = null
        customDestTransferRequest = null
        pendingSingleCreateDocEntry = null
        selectedEntryIds.clear()
        browserOpen = false
        entries.clear()
        password = ""
        confirmation = ""
        vaultSession?.close()
        vaultSession = null
        if (reasonMessage != null) message = reasonMessage
    }

    fun leaveVault() {
        lockVault()
        onBack()
    }

    // Prevent screenshots, screen recording, and recent-apps preview snapshots inside the Vault.
    DisposableEffect(activity) {
        val window = activity?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            lockVault()
        }
    }

    // Auto-lock when app goes to background (unless temporarily launching a SAF picker).
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (suppressNextBackgroundLock) {
                    suppressNextBackgroundLock = false
                } else if (vaultSession != null) {
                    lockVault(context.getString(R.string.vault_auto_locked_background))
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Idle auto-lock timer (3 minutes without user interaction, paused while playing media).
    LaunchedEffect(vaultSession, isMediaPlaying) {
        if (vaultSession == null) return@LaunchedEffect
        while (isActive) {
            delay(5_000L)
            if (!isMediaPlaying && !busy) {
                val idleMs = SystemClock.elapsedRealtime() - lastInteractionElapsedMs
                if (idleMs >= 180_000L) {
                    lockVault(context.getString(R.string.vault_auto_locked_idle))
                    break
                }
            } else {
                touchActivity()
            }
        }
    }

    // Cooldown countdown ticker for repeated wrong passwords.
    LaunchedEffect(lockoutUntilElapsedMs) {
        while (isActive) {
            val remainingMs = lockoutUntilElapsedMs - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) {
                remainingLockoutSeconds = 0
                break
            }
            remainingLockoutSeconds = ((remainingMs + 999L) / 1000L).toInt()
            delay(500L)
        }
    }

    BackHandler(enabled = viewingEntry != null || selectedEntryIds.isNotEmpty()) {
        when {
            viewingEntry != null -> viewingEntry = null
            selectedEntryIds.isNotEmpty() -> selectedEntryIds.clear()
        }
    }

    suspend fun refreshEntries(session: PrivateVaultSession) {
        val loaded = withContext(Dispatchers.IO) { repository.list(session) }
        entries.clear()
        entries.addAll(loaded)
    }

    fun executeRestoreBatch(
        targetEntries: List<PrivateVaultEntry>,
        destinationTree: Uri?,
        useOriginalFolder: Boolean,
        removeFromVault: Boolean
    ) {
        val session = vaultSession ?: return
        if (targetEntries.isEmpty()) return
        val cancelFlag = AtomicBoolean(false)
        activeCancelFlag = cancelFlag
        scope.launch {
            busy = true
            importProgress = 0
            importTotal = targetEntries.size
            byteFraction = 0f
            progressDetail = null
            message = null
            runCatching {
                val summary = withContext(Dispatchers.IO) {
                    treeSupport.restoreEntries(
                        entries = targetEntries,
                        destinationTree = destinationTree,
                        session = session,
                        useOriginalFolderWhenAvailable = useOriginalFolder,
                        removeFromVaultAfterRestore = removeFromVault,
                        isCancelled = { cancelFlag.get() },
                        onItemProgress = { current, total, name ->
                            importProgress = current
                            importTotal = total
                            progressDetail = name
                            byteFraction = 0f
                        },
                        onBytesProgress = { done, total ->
                            if (total > 0L) {
                                byteFraction = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                    )
                }
                refreshEntries(session)
                if (summary.succeededCount > 0) {
                    libraryViewModel.refresh(force = true)
                }
                summary
            }.onSuccess { summary ->
                if (removeFromVault) {
                    selectedEntryIds.clear()
                }
                message = if (removeFromVault) {
                    if (summary.failedCount == 0) {
                        context.getString(R.string.vault_take_out_result_success, summary.succeededCount)
                    } else {
                        context.getString(
                            R.string.vault_take_out_result_partial,
                            summary.succeededCount,
                            summary.failedCount
                        )
                    }
                } else {
                    if (summary.failedCount == 0) {
                        context.getString(R.string.vault_export_copy_result_success, summary.succeededCount)
                    } else {
                        context.getString(
                            R.string.vault_export_copy_result_partial,
                            summary.succeededCount,
                            summary.failedCount
                        )
                    }
                }
            }.onFailure { err ->
                runCatching { refreshEntries(session) }
                message = err.localizedMessage ?: context.getString(R.string.vault_single_export_failed)
            }
            activeCancelFlag = null
            importProgress = 0
            importTotal = 0
            progressDetail = null
            byteFraction = 0f
            busy = false
        }
    }

    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        suppressNextBackgroundLock = false
        touchActivity()
        val session = vaultSession ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val cancelFlag = AtomicBoolean(false)
        activeCancelFlag = cancelFlag
        scope.launch {
            busy = true
            importProgress = 0
            importTotal = uris.size
            byteFraction = 0f
            message = null
            var imported = 0
            runCatching {
                withContext(Dispatchers.IO) {
                    uris.forEachIndexed { idx, uri ->
                        if (cancelFlag.get()) return@withContext
                        val totalBytes = queryUriSize(context, uri)
                        val display = queryUriName(context, uri)
                        progressDetail = display
                        repository.importUri(uri, session) { doneBytes ->
                            if (totalBytes > 0L) {
                                byteFraction = (doneBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                        imported++
                        importProgress = idx + 1
                        byteFraction = 0f
                    }
                }
                refreshEntries(session)
            }.onSuccess {
                message = context.getString(R.string.vault_import_result_success, imported)
            }.onFailure {
                runCatching { refreshEntries(session) }
                message = context.getString(
                    R.string.vault_import_result_partial,
                    imported,
                    uris.size,
                    it.localizedMessage.orEmpty()
                )
            }
            activeCancelFlag = null
            importProgress = 0
            importTotal = 0
            progressDetail = null
            byteFraction = 0f
            busy = false
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        suppressNextBackgroundLock = false
        touchActivity()
        val session = vaultSession ?: return@rememberLauncherForActivityResult
        if (treeUri == null) return@rememberLauncherForActivityResult
        val move = moveFolderSelection
        scope.launch {
            busy = true
            importProgress = 0
            importTotal = 0
            progressDetail = null
            message = null
            runCatching {
                val result = withContext(Dispatchers.IO) {
                    treeSupport.importFolder(treeUri, session, move) { current, total, name ->
                        importProgress = current
                        importTotal = total
                        progressDetail = name
                    }
                }
                if (move && result.deletedSourceUris.isNotEmpty()) {
                    libraryViewModel.reconcileMovedMediaUris(result.deletedSourceUris)
                }
                refreshEntries(session)
                result
            }.onSuccess { result ->
                message = if (move) {
                    if (result.sourceFullyMoved) {
                        context.getString(R.string.vault_folder_import_moved, result.fileCount)
                    } else {
                        context.getString(
                            R.string.vault_folder_import_partial_move,
                            result.fileCount,
                            result.deletedSourceNodeCount,
                            result.sourceNodeCount
                        )
                    }
                } else {
                    context.getString(R.string.vault_folder_import_copied, result.fileCount)
                }
            }.onFailure {
                message = it.localizedMessage ?: context.getString(R.string.vault_folder_import_failed)
            }
            importProgress = 0
            importTotal = 0
            progressDetail = null
            busy = false
        }
    }

    val customDestRestorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        suppressNextBackgroundLock = false
        touchActivity()
        val pending = customDestTransferRequest
        customDestTransferRequest = null
        if (treeUri == null || pending == null) return@rememberLauncherForActivityResult
        executeRestoreBatch(
            targetEntries = pending.entries,
            destinationTree = treeUri,
            useOriginalFolder = false,
            removeFromVault = pending.removeFromVault
        )
    }

    val browserSourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        suppressNextBackgroundLock = false
        touchActivity()
        if (treeUri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
        vaultPrefs.edit().putString("source_tree_uri", treeUri.toString()).apply()
        browserTreeUri = treeUri
        browserOpen = true
    }

    val singleDocumentExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        suppressNextBackgroundLock = false
        touchActivity()
        val session = vaultSession ?: return@rememberLauncherForActivityResult
        val entry = pendingSingleCreateDocEntry ?: return@rememberLauncherForActivityResult
        pendingSingleCreateDocEntry = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            byteFraction = 0f
            progressDetail = entry.displayName
            message = null
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.exportToDocumentVerified(entry.id, uri, session) { doneBytes ->
                        if (entry.sizeBytes > 0L) {
                            byteFraction = (doneBytes.toFloat() / entry.sizeBytes.toFloat()).coerceIn(0f, 1f)
                        }
                    }
                }
                libraryViewModel.refresh(force = true)
            }.onSuccess {
                message = context.getString(R.string.vault_single_export_success)
            }.onFailure {
                message = it.localizedMessage ?: context.getString(R.string.vault_single_export_failed)
            }
            progressDetail = null
            byteFraction = 0f
            busy = false
        }
    }

    fun openInternalBrowser() {
        touchActivity()
        scope.launch {
            val candidate = browserTreeUri
            val validUri = if (candidate != null) {
                val readable = withContext(Dispatchers.IO) { treeSupport.isTreeReadable(candidate) }
                if (readable) candidate else null
            } else {
                null
            }
            if (validUri == null && candidate != null) {
                browserTreeUri = null
            }
            browserOpen = true
        }
    }

    fun requestTransfer(targetEntries: List<PrivateVaultEntry>, removeFromVault: Boolean) {
        if (targetEntries.isEmpty()) return
        touchActivity()
        transferSheetRequest = PendingTransferRequest(
            entries = targetEntries,
            removeFromVault = removeFromVault
        )
    }

    fun importBrowserItems(selected: List<SelectedVaultBrowserItem>, move: Boolean) {
        val session = vaultSession ?: return
        if (selected.isEmpty()) return
        browserOpen = false
        val cancelFlag = AtomicBoolean(false)
        activeCancelFlag = cancelFlag
        scope.launch {
            busy = true
            importProgress = 0
            importTotal = selected.size
            byteFraction = 0f
            progressDetail = null
            message = null
            runCatching {
                val result = withContext(Dispatchers.IO) {
                    treeSupport.importBrowserSelection(
                        items = selected,
                        session = session,
                        moveSource = move,
                        isCancelled = { cancelFlag.get() },
                        onItemProgress = { current, total, name ->
                            importProgress = current
                            importTotal = total
                            progressDetail = name
                            byteFraction = 0f
                        },
                        onBytesProgress = { done, total ->
                            if (total > 0L) {
                                byteFraction = (done.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            }
                        }
                    )
                }
                if (move && result.deletedSourceMediaIds.isNotEmpty()) {
                    libraryViewModel.reconcileMovedMediaUris(result.deletedSourceMediaIds)
                }
                refreshEntries(session)
                result
            }.onSuccess { result ->
                message = if (move) {
                    if (result.copiedOnlyCount == 0 && result.failedCount == 0) {
                        context.getString(R.string.vault_browser_move_result_success, result.movedCount)
                    } else {
                        context.getString(
                            R.string.vault_browser_move_result_partial,
                            result.importedCount,
                            result.movedCount,
                            result.copiedOnlyCount,
                            result.failedCount
                        )
                    }
                } else {
                    if (result.failedCount == 0) {
                        context.getString(R.string.vault_browser_copy_result_success, result.importedCount)
                    } else {
                        context.getString(
                            R.string.vault_browser_copy_result_partial,
                            result.importedCount,
                            result.failedCount
                        )
                    }
                }
            }.onFailure { err ->
                runCatching { refreshEntries(session) }
                message = err.localizedMessage ?: context.getString(R.string.vault_folder_import_failed)
            }
            activeCancelFlag = null
            importProgress = 0
            importTotal = 0
            progressDetail = null
            byteFraction = 0f
            busy = false
        }
    }

    fun importSubfolderFromBrowser(
        treeUri: Uri,
        folderDocId: String,
        parentDocId: String?,
        folderName: String,
        displayPath: String,
        move: Boolean
    ) {
        val session = vaultSession ?: return
        browserOpen = false
        scope.launch {
            busy = true
            importProgress = 0
            importTotal = 0
            progressDetail = folderName
            message = null
            runCatching {
                val result = withContext(Dispatchers.IO) {
                    treeSupport.importFolder(
                        treeUri = treeUri,
                        session = session,
                        moveSource = move,
                        rootDocumentId = folderDocId,
                        sourceParentDocumentId = parentDocId,
                        sourceDisplayPath = displayPath,
                        onProgress = { current, total, name ->
                            importProgress = current
                            importTotal = total
                            progressDetail = name
                        }
                    )
                }
                if (move && result.deletedSourceUris.isNotEmpty()) {
                    libraryViewModel.reconcileMovedMediaUris(result.deletedSourceUris)
                }
                refreshEntries(session)
                result
            }.onSuccess { result ->
                message = if (move) {
                    if (result.sourceFullyMoved) {
                        context.getString(R.string.vault_folder_import_moved, result.fileCount)
                    } else {
                        context.getString(
                            R.string.vault_folder_import_partial_move,
                            result.fileCount,
                            result.deletedSourceNodeCount,
                            result.sourceNodeCount
                        )
                    }
                } else {
                    context.getString(R.string.vault_folder_import_copied, result.fileCount)
                }
            }.onFailure { err ->
                message = err.localizedMessage ?: context.getString(R.string.vault_folder_import_failed)
            }
            importProgress = 0
            importTotal = 0
            progressDetail = null
            busy = false
        }
    }

    val filteredEntries = remember(entries.toList(), searchQuery, activeFilter, activeSort, sortAscending) {
        filterAndSortVaultEntries(
            entries = entries.toList(),
            query = searchQuery,
            filter = activeFilter,
            sort = activeSort,
            ascending = sortAscending
        )
    }

    val totalVaultBytes = remember(entries.toList()) {
        entries.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    }

    val allFilteredSelected = remember(filteredEntries, selectedEntryIds.toList()) {
        filteredEntries.isNotEmpty() && filteredEntries.all { it.id in selectedEntryIds }
    }

    Scaffold(
        containerColor = Color(0xFF0D0D12),
        topBar = {
            TopAppBar(
                title = {
                    if (selectedEntryIds.isNotEmpty()) {
                        Text(stringResource(R.string.vault_selected_count, selectedEntryIds.size))
                    } else {
                        Text(stringResource(R.string.vault_title))
                    }
                },
                navigationIcon = {
                    if (selectedEntryIds.isNotEmpty()) {
                        IconButton(onClick = { selectedEntryIds.clear() }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_cancel))
                        }
                    } else {
                        IconButton(onClick = ::leaveVault) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    }
                },
                actions = {
                    if (vaultSession != null) {
                        if (selectedEntryIds.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    touchActivity()
                                    if (allFilteredSelected) {
                                        filteredEntries.forEach { selectedEntryIds.remove(it.id) }
                                    } else {
                                        filteredEntries.forEach { entry ->
                                            if (entry.id !in selectedEntryIds) selectedEntryIds.add(entry.id)
                                        }
                                    }
                                },
                                enabled = !busy && filteredEntries.isNotEmpty()
                            ) {
                                Icon(
                                    imageVector = if (allFilteredSelected) Icons.Filled.CheckBox else Icons.Filled.SelectAll,
                                    contentDescription = stringResource(
                                        if (allFilteredSelected) R.string.vault_deselect_all else R.string.vault_select_all
                                    )
                                )
                            }
                            IconButton(
                                onClick = { showBatchDeleteConfirm = true },
                                enabled = !busy
                            ) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.common_delete))
                            }
                        } else {
                            IconButton(
                                onClick = {
                                    touchActivity()
                                    showChangePasswordDialog = true
                                },
                                enabled = !busy
                            ) {
                                Icon(Icons.Filled.Key, contentDescription = stringResource(R.string.vault_change_password))
                            }
                            IconButton(
                                onClick = { lockVault() },
                                enabled = !busy
                            ) {
                                Icon(Icons.Filled.Lock, contentDescription = stringResource(R.string.vault_lock))
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (vaultSession != null && !busy && selectedEntryIds.isEmpty()) {
                FloatingActionButton(
                    onClick = { openInternalBrowser() },
                    containerColor = Color(0xFFA855F7),
                    contentColor = Color.White
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.vault_import_files))
                }
            }
        },
        bottomBar = {
            if (vaultSession != null && selectedEntryIds.isNotEmpty()) {
                val chosenEntries = remember(entries.toList(), selectedEntryIds.toList()) {
                    entries.filter { it.id in selectedEntryIds }
                }
                Surface(
                    color = Color(0xFF171724),
                    tonalElevation = 8.dp,
                    border = BorderStroke(1.dp, Color(0xFF2E2644)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { requestTransfer(chosenEntries, removeFromVault = true) },
                            enabled = !busy && chosenEntries.isNotEmpty(),
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
                                stringResource(R.string.vault_take_out),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { requestTransfer(chosenEntries, removeFromVault = false) },
                            enabled = !busy && chosenEntries.isNotEmpty(),
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
                                stringResource(R.string.vault_export_copy),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { showBatchDeleteConfirm = true },
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (vaultSession == null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                        border = BorderStroke(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(50.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = stringResource(R.string.vault_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = stringResource(R.string.vault_shield_active),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Text(
                        text = if (configured) {
                            stringResource(R.string.vault_unlock_prompt)
                        } else {
                            stringResource(R.string.vault_create_prompt)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(stringResource(R.string.vault_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (!configured) {
                        PasswordStrengthRow(password)
                        OutlinedTextField(
                            value = confirmation,
                            onValueChange = { confirmation = it },
                            label = { Text(stringResource(R.string.vault_confirm_password_label)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (remainingLockoutSeconds > 0) {
                        Text(
                            text = stringResource(R.string.vault_lockout_wait, remainingLockoutSeconds),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                message = null
                                val pwdChars = password.toCharArray()
                                val confirmChars = confirmation.toCharArray()
                                val result = runCatching {
                                    if (!configured) {
                                        require(pwdChars.size >= 8) {
                                            context.getString(R.string.vault_password_too_short)
                                        }
                                        require(pwdChars.contentEquals(confirmChars)) {
                                            context.getString(R.string.vault_password_mismatch)
                                        }
                                        withContext(Dispatchers.IO) {
                                            val s = repository.create(pwdChars)
                                            s to emptyList<PrivateVaultEntry>()
                                        }
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            val s = repository.openSession(pwdChars)
                                            val list = repository.list(s)
                                            s to list
                                        }
                                    }
                                }
                                pwdChars.fill('\u0000')
                                confirmChars.fill('\u0000')
                                result.onSuccess { (session, list) ->
                                    configured = true
                                    failedAttempts = 0
                                    lockoutUntilElapsedMs = 0L
                                    entries.clear()
                                    entries.addAll(list)
                                    vaultSession = session
                                    password = ""
                                    confirmation = ""
                                    touchActivity()
                                }.onFailure { err ->
                                    failedAttempts++
                                    if (configured && failedAttempts >= 3) {
                                        val waitSec = when {
                                            failedAttempts >= 6 -> 60L
                                            failedAttempts == 5 -> 30L
                                            failedAttempts == 4 -> 15L
                                            else -> 5L
                                        }
                                        lockoutUntilElapsedMs = SystemClock.elapsedRealtime() + waitSec * 1_000L
                                    }
                                    message = if (configured) {
                                        context.getString(R.string.vault_wrong_password)
                                    } else {
                                        err.localizedMessage ?: context.getString(R.string.vault_open_failed)
                                    }
                                }
                                busy = false
                            }
                        },
                        enabled = !busy && remainingLockoutSeconds == 0 && password.length >= 8 &&
                            (configured || password == confirmation),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (configured) stringResource(R.string.vault_unlock_button)
                            else stringResource(R.string.vault_create_button)
                        )
                    }
                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                val activeSession = vaultSession!!
                // Summary & folder import bar
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.vault_summary_stats,
                                    entries.size,
                                    formatVaultBytes(context, totalVaultBytes)
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(R.string.vault_shield_active),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    touchActivity()
                                    moveFolderSelection = false
                                    suppressNextBackgroundLock = true
                                    folderPicker.launch(null)
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.vault_copy_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = {
                                    touchActivity()
                                    moveFolderSelection = true
                                    suppressNextBackgroundLock = true
                                    folderPicker.launch(null)
                                },
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.vault_move_folder), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            OutlinedButton(
                                onClick = { openInternalBrowser() },
                                enabled = !busy,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.vault_browse), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                // Search & sort row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = {
                            touchActivity()
                            searchQuery = it
                        },
                        placeholder = { Text(stringResource(R.string.vault_search_hint)) },
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
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.vault_sort_label))
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            VaultEntrySort.entries.forEach { sortOption ->
                                val labelRes = when (sortOption) {
                                    VaultEntrySort.NEWEST -> R.string.vault_sort_newest
                                    VaultEntrySort.NAME -> R.string.vault_sort_name
                                    VaultEntrySort.SIZE -> R.string.vault_sort_size
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (activeSort == sortOption) "✓ " else "") +
                                                stringResource(labelRes)
                                        )
                                    },
                                    onClick = {
                                        if (activeSort == sortOption) {
                                            sortAscending = !sortAscending
                                        } else {
                                            activeSort = sortOption
                                            sortAscending = sortOption == VaultEntrySort.NAME
                                        }
                                        showSortMenu = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (sortAscending) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                                        contentDescription = null
                                    )
                                },
                                text = {
                                    Text(
                                        stringResource(
                                            if (sortAscending) R.string.vault_browser_sort_ascending
                                            else R.string.vault_browser_sort_descending
                                        )
                                    )
                                },
                                onClick = {
                                    sortAscending = !sortAscending
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }

                // Type filter chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VaultEntryFilter.entries.forEach { filter ->
                        val labelRes = when (filter) {
                            VaultEntryFilter.ALL -> R.string.vault_filter_all
                            VaultEntryFilter.VIDEO -> R.string.vault_filter_video
                            VaultEntryFilter.AUDIO -> R.string.vault_filter_audio
                            VaultEntryFilter.IMAGE -> R.string.vault_filter_image
                            VaultEntryFilter.OTHER -> R.string.vault_filter_other
                        }
                        FilterChip(
                            selected = activeFilter == filter,
                            onClick = {
                                touchActivity()
                                activeFilter = filter
                            },
                            label = { Text(stringResource(labelRes)) }
                        )
                    }
                }

                if (busy) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val combinedFraction = when {
                            importTotal > 0 -> ((importProgress.toFloat() + byteFraction) / importTotal.toFloat()).coerceIn(0f, 1f)
                            byteFraction > 0f -> byteFraction.coerceIn(0f, 1f)
                            else -> null
                        }
                        if (combinedFraction != null) {
                            LinearProgressIndicator(progress = { combinedFraction }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val statusText = buildString {
                                if (importTotal > 0) append("$importProgress / $importTotal ")
                                progressDetail?.let { append(it) }
                            }.ifBlank { stringResource(R.string.vault_processing) }
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                if (filteredEntries.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (entries.isEmpty()) {
                                stringResource(R.string.vault_empty_state)
                            } else {
                                stringResource(R.string.vault_no_search_results)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 84.dp)
                    ) {
                        items(filteredEntries, key = { it.id }) { entry ->
                            val isSelected = entry.id in selectedEntryIds
                            var rowMenuOpen by remember(entry.id) { mutableStateOf(false) }

                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        touchActivity()
                                        if (selectedEntryIds.isNotEmpty()) {
                                            if (isSelected) selectedEntryIds.remove(entry.id)
                                            else selectedEntryIds.add(entry.id)
                                        } else if (entry.mimeType == PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME) {
                                            requestTransfer(listOf(entry), removeFromVault = true)
                                        } else {
                                            viewingEntry = entry
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = {
                                            touchActivity()
                                            if (isSelected) selectedEntryIds.remove(entry.id)
                                            else selectedEntryIds.add(entry.id)
                                        },
                                        enabled = !busy
                                    ) {
                                        Icon(
                                            imageVector = if (isSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                            contentDescription = stringResource(R.string.vault_select_item),
                                            tint = if (isSelected) Color(0xFFA855F7) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    VaultEntryThumbnail(
                                        entry = entry,
                                        repository = repository,
                                        session = activeSession
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val sizeLabel = formatVaultBytes(context, entry.sizeBytes)
                                        Text(
                                            if (entry.mimeType == PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME) {
                                                stringResource(R.string.vault_encrypted_folder_subtitle, sizeLabel)
                                            } else {
                                                "${entry.mimeType} · $sizeLabel"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (selectedEntryIds.isEmpty()) {
                                        Box {
                                            IconButton(
                                                onClick = {
                                                    touchActivity()
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
                                                        requestTransfer(listOf(entry), removeFromVault = true)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                                                    text = { Text(stringResource(R.string.vault_export_copy)) },
                                                    onClick = {
                                                        rowMenuOpen = false
                                                        requestTransfer(listOf(entry), removeFromVault = false)
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                                    text = { Text(stringResource(R.string.vault_file_info)) },
                                                    onClick = {
                                                        rowMenuOpen = false
                                                        infoEntry = entry
                                                    }
                                                )
                                                DropdownMenuItem(
                                                    leadingIcon = {
                                                        Icon(
                                                            Icons.Filled.Delete,
                                                            contentDescription = null,
                                                            tint = MaterialTheme.colorScheme.error
                                                        )
                                                    },
                                                    text = {
                                                        Text(
                                                            stringResource(R.string.common_delete),
                                                            color = MaterialTheme.colorScheme.error
                                                        )
                                                    },
                                                    onClick = {
                                                        rowMenuOpen = false
                                                        pendingDelete = entry
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
    }

    // Internal file browser modal (opened from + FAB or Browse button)
    if (browserOpen && vaultSession != null) {
        VaultInternalBrowserModal(
            treeSupport = treeSupport,
            rootTreeUri = browserTreeUri,
            busy = busy,
            onTouchActivity = ::touchActivity,
            onPickRootFolder = {
                suppressNextBackgroundLock = true
                browserSourcePicker.launch(null)
            },
            onOpenSystemPickerFallback = {
                browserOpen = false
                suppressNextBackgroundLock = true
                systemPicker.launch(arrayOf("*/*"))
            },
            onCopyOrMoveSelection = ::importBrowserItems,
            onImportSubfolder = ::importSubfolderFromBrowser,
            onDismiss = { browserOpen = false }
        )
    }

    // Transfer destination & confirmation dialog (Take out vs Export copy)
    transferSheetRequest?.let { req ->
        val canRestoreOriginal = remember(req) {
            treeSupport.canRestoreAllToOriginalFolder(req.entries)
        }
        val singleOriginLabel = remember(req) {
            req.entries.singleOrNull()?.sourceDisplayPath?.takeIf { it.isNotBlank() }
        }
        AlertDialog(
            onDismissRequest = { transferSheetRequest = null },
            title = {
                Text(
                    stringResource(
                        if (req.removeFromVault) R.string.vault_take_out_dialog_title
                        else R.string.vault_export_copy_dialog_title
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        stringResource(
                            if (req.removeFromVault) R.string.vault_take_out_dialog_message
                            else R.string.vault_export_copy_dialog_message,
                            req.entries.size
                        )
                    )
                    if (!canRestoreOriginal) {
                        Text(
                            text = stringResource(R.string.vault_restore_to_original_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (singleOriginLabel != null) {
                        Text(
                            text = stringResource(R.string.vault_info_original_location, singleOriginLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFD8B4FE)
                        )
                    }
                    if (canRestoreOriginal) {
                        Button(
                            onClick = {
                                transferSheetRequest = null
                                if (req.removeFromVault) {
                                    confirmOriginalRestoreRequest = req
                                } else {
                                    executeRestoreBatch(
                                        targetEntries = req.entries,
                                        destinationTree = null,
                                        useOriginalFolder = true,
                                        removeFromVault = false
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFA855F7),
                                contentColor = Color.White
                            )
                        ) {
                            Text(stringResource(R.string.vault_restore_to_original))
                        }
                    }
                    OutlinedButton(
                        onClick = {
                            transferSheetRequest = null
                            val singleNonFolder = req.entries.singleOrNull()?.takeIf {
                                !req.removeFromVault && it.mimeType != PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME
                            }
                            if (singleNonFolder != null && !canRestoreOriginal) {
                                pendingSingleCreateDocEntry = singleNonFolder
                                suppressNextBackgroundLock = true
                                singleDocumentExporter.launch(singleNonFolder.displayName)
                            } else {
                                customDestTransferRequest = req
                                suppressNextBackgroundLock = true
                                customDestRestorePicker.launch(null)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.vault_restore_choose_folder))
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { transferSheetRequest = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Explicit confirmation before removing decrypted files from vault
    confirmOriginalRestoreRequest?.let { req ->
        AlertDialog(
            onDismissRequest = { confirmOriginalRestoreRequest = null },
            title = { Text(stringResource(R.string.vault_confirm_take_out_title)) },
            text = { Text(stringResource(R.string.vault_confirm_take_out_body, req.entries.size)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOriginalRestoreRequest = null
                        executeRestoreBatch(
                            targetEntries = req.entries,
                            destinationTree = null,
                            useOriginalFolder = true,
                            removeFromVault = true
                        )
                    }
                ) {
                    Text(stringResource(R.string.vault_confirm_action), color = Color(0xFFA855F7))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmOriginalRestoreRequest = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // File info dialog
    infoEntry?.let { entry ->
        val dateText = remember(entry.addedAtMs) {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(entry.addedAtMs))
        }
        AlertDialog(
            onDismissRequest = { infoEntry = null },
            title = { Text(stringResource(R.string.vault_file_info)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.vault_info_name, entry.displayName))
                    Text(stringResource(R.string.vault_info_type, entry.mimeType))
                    Text(stringResource(R.string.vault_info_size, formatVaultBytes(context, entry.sizeBytes)))
                    Text(stringResource(R.string.vault_info_added_at, dateText))
                    Text(
                        if (!entry.sourceDisplayPath.isNullOrBlank()) {
                            stringResource(R.string.vault_info_original_location, entry.sourceDisplayPath)
                        } else {
                            stringResource(R.string.vault_info_original_unavailable)
                        }
                    )
                    Text(
                        stringResource(R.string.vault_info_encryption),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { infoEntry = null }) {
                    Text(stringResource(R.string.common_close))
                }
            }
        )
    }

    // Isolated in-vault viewer (zero unencrypted files on disk!)
    val currentViewing = viewingEntry
    val currentSession = vaultSession
    if (currentViewing != null && currentSession != null) {
        VaultMediaViewerDialog(
            entry = currentViewing,
            repository = repository,
            session = currentSession,
            entries = entries.toList(),
            onPlaybackActiveChanged = { isMediaPlaying = it },
            onDismiss = {
                isMediaPlaying = false
                viewingEntry = null
                touchActivity()
            }
        )
    }

    // Change password dialog (instant Master Key re-wrapping)
    if (showChangePasswordDialog && vaultSession != null) {
        var currentPwd by remember { mutableStateOf("") }
        var newPwd by remember { mutableStateOf("") }
        var confirmNewPwd by remember { mutableStateOf("") }
        var dialogError by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { if (!busy) showChangePasswordDialog = false },
            title = { Text(stringResource(R.string.vault_change_password)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = currentPwd,
                        onValueChange = { currentPwd = it },
                        label = { Text(stringResource(R.string.vault_current_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPwd,
                        onValueChange = { newPwd = it },
                        label = { Text(stringResource(R.string.vault_new_password)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    PasswordStrengthRow(newPwd)
                    OutlinedTextField(
                        value = confirmNewPwd,
                        onValueChange = { confirmNewPwd = it },
                        label = { Text(stringResource(R.string.vault_confirm_password_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    dialogError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && currentPwd.length >= 8 && newPwd.length >= 8 && newPwd == confirmNewPwd,
                    onClick = {
                        val session = vaultSession ?: return@TextButton
                        scope.launch {
                            busy = true
                            dialogError = null
                            val curChars = currentPwd.toCharArray()
                            val newChars = newPwd.toCharArray()
                            val result = runCatching {
                                withContext(Dispatchers.IO) {
                                    repository.changePassword(session, curChars, newChars)
                                }
                            }
                            curChars.fill('\u0000')
                            newChars.fill('\u0000')
                            result.onSuccess { updatedSession ->
                                vaultSession = updatedSession
                                showChangePasswordDialog = false
                                message = context.getString(R.string.vault_password_changed)
                            }.onFailure {
                                dialogError = context.getString(R.string.vault_wrong_password)
                            }
                            busy = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_save))
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showChangePasswordDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    // Batch delete confirmation
    if (showBatchDeleteConfirm && vaultSession != null) {
        val count = selectedEntryIds.size
        AlertDialog(
            onDismissRequest = { if (!busy) showBatchDeleteConfirm = false },
            title = { Text(stringResource(R.string.vault_batch_delete_title)) },
            text = { Text(stringResource(R.string.vault_batch_delete_message, count)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val session = vaultSession ?: return@TextButton
                        val ids = selectedEntryIds.toList()
                        showBatchDeleteConfirm = false
                        scope.launch {
                            busy = true
                            importTotal = ids.size
                            importProgress = 0
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    ids.forEachIndexed { idx, id ->
                                        repository.delete(id, session)
                                        importProgress = idx + 1
                                    }
                                }
                                refreshEntries(session)
                            }.onSuccess {
                                selectedEntryIds.clear()
                                message = context.getString(R.string.vault_batch_deleted, ids.size)
                            }.onFailure {
                                message = it.localizedMessage ?: context.getString(R.string.vault_delete_failed)
                            }
                            importProgress = 0
                            importTotal = 0
                            busy = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchDeleteConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.vault_delete_single_title)) },
            text = { Text(stringResource(R.string.vault_delete_single_message, entry.displayName)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = entry
                        val session = vaultSession
                        pendingDelete = null
                        if (session != null) {
                            scope.launch {
                                busy = true
                                runCatching {
                                    withContext(Dispatchers.IO) { repository.delete(target.id, session) }
                                    refreshEntries(session)
                                }.onFailure { message = it.localizedMessage }
                                busy = false
                            }
                        }
                    }
                ) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun PasswordStrengthRow(password: String) {
    if (password.isEmpty()) return
    val hasDigit = password.any { it.isDigit() }
    val hasUpper = password.any { it.isUpperCase() }
    val hasLower = password.any { it.isLowerCase() }
    val hasSymbol = password.any { !it.isLetterOrDigit() }
    val score = listOf(
        password.length >= 8,
        password.length >= 12,
        hasDigit,
        hasUpper && hasLower,
        hasSymbol
    ).count { it }
    val (labelRes, color) = when {
        password.length < 8 || score <= 1 -> R.string.vault_strength_weak to MaterialTheme.colorScheme.error
        score <= 3 -> R.string.vault_strength_medium to MaterialTheme.colorScheme.tertiary
        else -> R.string.vault_strength_strong to MaterialTheme.colorScheme.primary
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { (score / 5f).coerceIn(0.15f, 1f) },
            color = color,
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
        )
        Text(
            text = stringResource(R.string.vault_password_strength_prefix, stringResource(labelRes)),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun VaultEntryThumbnail(
    entry: PrivateVaultEntry,
    repository: PrivateVaultRepository,
    session: PrivateVaultSession
) {
    var bitmap by remember(entry.id, session) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(entry.id, session) {
        if (!entry.mimeType.startsWith("image/") && !entry.mimeType.startsWith("video/")) return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            val bytes = repository.loadThumbnailBytes(entry.id, session) ?: return@withContext null
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } finally {
                bytes.fill(0)
            }
        }
        bitmap = decoded
    }

    DisposableEffect(entry.id) {
        onDispose {
            bitmap?.recycle()
            bitmap = null
        }
    }

    val boxModifier = Modifier
        .size(48.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = boxModifier
        )
    } else {
        Box(modifier = boxModifier, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = when {
                    entry.mimeType == PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME -> Icons.Filled.Folder
                    entry.mimeType.startsWith("video/") -> Icons.Filled.VideoFile
                    entry.mimeType.startsWith("audio/") -> Icons.Filled.Audiotrack
                    entry.mimeType.startsWith("image/") -> Icons.Filled.Image
                    else -> Icons.Filled.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun queryUriName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return cursor.getString(idx).orEmpty()
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
}

private fun queryUriSize(context: Context, uri: Uri): Long {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && !cursor.isNull(idx)) return cursor.getLong(idx)
        }
    }
    return -1L
}

private tailrec fun Context.findVaultActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findVaultActivity()
    else -> null
}
