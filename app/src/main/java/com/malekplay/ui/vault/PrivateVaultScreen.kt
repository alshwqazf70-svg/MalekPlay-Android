package com.malekplay.ui.vault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.malekplay.vault.VaultBrowserItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class VaultFilter(val labelRes: Int) {
    ALL(R.string.vault_filter_all),
    VIDEO(R.string.vault_filter_video),
    AUDIO(R.string.vault_filter_audio),
    IMAGE(R.string.vault_filter_image),
    OTHER(R.string.vault_filter_other)
}

private enum class VaultSort(val labelRes: Int) {
    NEWEST(R.string.vault_sort_newest),
    NAME(R.string.vault_sort_name),
    SIZE(R.string.vault_sort_size)
}

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
    var activeFilter by remember { mutableStateOf(VaultFilter.ALL) }
    var activeSort by remember { mutableStateOf(VaultSort.NEWEST) }
    var showSortMenu by remember { mutableStateOf(false) }
    val selectedEntryIds = remember { mutableStateListOf<String>() }
    var showChangePasswordDialog by remember { mutableStateOf(false) }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // Security hardening: brute-force backoff + background/idle auto-lock
    var failedAttempts by remember { mutableIntStateOf(0) }
    var lockoutUntilElapsedMs by remember { mutableLongStateOf(0L) }
    var remainingLockoutSeconds by remember { mutableIntStateOf(0) }
    var lastInteractionElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var suppressNextBackgroundLock by remember { mutableStateOf(false) }

    // SAF / operation state
    var pendingExport by remember { mutableStateOf<PrivateVaultEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<PrivateVaultEntry?>(null) }
    var pendingFolderRestore by remember { mutableStateOf<PrivateVaultEntry?>(null) }
    var moveFolderSelection by remember { mutableStateOf(false) }
    val vaultPrefs = remember(context) {
        context.getSharedPreferences("private_vault_browser", Context.MODE_PRIVATE)
    }
    var browserTreeUri by remember {
        mutableStateOf(vaultPrefs.getString("source_tree_uri", null)?.let(Uri::parse))
    }
    var browserOpen by remember { mutableStateOf(false) }
    var browserLoading by remember { mutableStateOf(false) }
    val browserPath = remember { mutableStateListOf<Pair<String, String>>() }
    val browserItems = remember { mutableStateListOf<VaultBrowserItem>() }
    val selectedUris = remember { mutableStateListOf<String>() }

    var importProgress by remember { mutableIntStateOf(0) }
    var importTotal by remember { mutableIntStateOf(0) }
    var progressDetail by remember { mutableStateOf<String?>(null) }
    var byteFraction by remember { mutableFloatStateOf(0f) }

    fun touchActivity() {
        lastInteractionElapsedMs = SystemClock.elapsedRealtime()
    }

    fun lockVault(reasonMessage: String? = null) {
        viewingEntry = null
        isMediaPlaying = false
        showChangePasswordDialog = false
        showBatchDeleteConfirm = false
        pendingExport = null
        pendingDelete = null
        pendingFolderRestore = null
        selectedEntryIds.clear()
        browserOpen = false
        browserItems.clear()
        browserPath.clear()
        selectedUris.clear()
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

    BackHandler(enabled = viewingEntry != null || browserOpen || selectedEntryIds.isNotEmpty()) {
        when {
            viewingEntry != null -> viewingEntry = null
            selectedEntryIds.isNotEmpty() -> selectedEntryIds.clear()
            browserPath.isNotEmpty() -> {
                browserPath.removeAt(browserPath.lastIndex)
                val uri = browserTreeUri
                if (uri != null) {
                    scope.launch {
                        browserLoading = true
                        runCatching {
                            withContext(Dispatchers.IO) {
                                treeSupport.listBrowserChildren(uri, browserPath.lastOrNull()?.first)
                            }
                        }.onSuccess {
                            browserItems.clear()
                            browserItems.addAll(it)
                        }.onFailure { message = it.localizedMessage }
                        browserLoading = false
                    }
                }
            }
            else -> browserOpen = false
        }
    }

    suspend fun refreshEntries(session: PrivateVaultSession) {
        val loaded = withContext(Dispatchers.IO) { repository.list(session) }
        entries.clear()
        entries.addAll(loaded)
    }

    fun openBrowserFolder(treeUri: Uri, folderId: String?, folderName: String?) {
        touchActivity()
        scope.launch {
            browserLoading = true
            runCatching {
                withContext(Dispatchers.IO) { treeSupport.listBrowserChildren(treeUri, folderId) }
            }.onSuccess {
                if (folderId != null && folderName != null) browserPath.add(folderId to folderName)
                browserItems.clear()
                browserItems.addAll(it)
                browserOpen = true
            }.onFailure { message = it.localizedMessage }
            browserLoading = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        suppressNextBackgroundLock = false
        touchActivity()
        val session = vaultSession ?: return@rememberLauncherForActivityResult
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
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
                message = "تم استيراد $imported عنصر بأمان إلى الخزنة."
            }.onFailure {
                runCatching { refreshEntries(session) }
                message = "تم استيراد $imported من ${uris.size} قبل التوقف: ${it.localizedMessage}"
            }
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
                    libraryViewModel.markMediaDeletedExternally(result.deletedSourceUris)
                }
                refreshEntries(session)
                result
            }.onSuccess { result ->
                message = if (move) {
                    if (result.sourceFullyMoved) {
                        "تم نقل المجلد (${result.fileCount} ملف) وحذف المصدر بنجاح."
                    } else {
                        "تم حفظ نسخة مشفرة (${result.fileCount} ملف)، وحُذف ${result.deletedSourceNodeCount} من أصل ${result.sourceNodeCount}."
                    }
                } else {
                    "تم نسخ المجلد مشفراً (${result.fileCount} ملف) إلى الخزنة."
                }
            }.onFailure {
                message = it.localizedMessage ?: "تعذر استيراد المجلد."
            }
            importProgress = 0
            importTotal = 0
            progressDetail = null
            busy = false
        }
    }

    val folderRestorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        suppressNextBackgroundLock = false
        touchActivity()
        val session = vaultSession ?: return@rememberLauncherForActivityResult
        val target = pendingFolderRestore ?: return@rememberLauncherForActivityResult
        pendingFolderRestore = null
        if (treeUri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            message = null
            runCatching {
                withContext(Dispatchers.IO) { treeSupport.restoreFolder(target, treeUri, session) }
            }.onSuccess {
                message = "تمت استعادة المجلد ومحتوياته بنجاح."
            }.onFailure {
                message = it.localizedMessage ?: "تعذرت استعادة المجلد."
            }
            busy = false
        }
    }

    val browserSourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        suppressNextBackgroundLock = false
        touchActivity()
        if (treeUri == null) return@rememberLauncherForActivityResult
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
        vaultPrefs.edit().putString("source_tree_uri", treeUri.toString()).apply()
        browserTreeUri = treeUri
        browserPath.clear()
        selectedUris.clear()
        openBrowserFolder(treeUri, null, null)
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        suppressNextBackgroundLock = false
        touchActivity()
        val session = vaultSession ?: return@rememberLauncherForActivityResult
        val entry = pendingExport ?: return@rememberLauncherForActivityResult
        pendingExport = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            byteFraction = 0f
            progressDetail = entry.displayName
            message = null
            runCatching {
                withContext(Dispatchers.IO) {
                    repository.exportTo(entry.id, uri, session) { doneBytes ->
                        if (entry.sizeBytes > 0L) {
                            byteFraction = (doneBytes.toFloat() / entry.sizeBytes.toFloat()).coerceIn(0f, 1f)
                        }
                    }
                }
            }.onSuccess {
                message = "تم تصدير النسخة المفكوكة بنجاح."
            }.onFailure {
                message = it.localizedMessage ?: "تعذر تصدير الملف."
            }
            progressDetail = null
            byteFraction = 0f
            busy = false
        }
    }

    val filteredEntries = remember(entries.toList(), searchQuery, activeFilter, activeSort) {
        val q = searchQuery.trim().lowercase(Locale.getDefault())
        val base = entries.filter { entry ->
            val matchesQuery = q.isEmpty() || entry.displayName.lowercase(Locale.getDefault()).contains(q)
            val matchesType = when (activeFilter) {
                VaultFilter.ALL -> true
                VaultFilter.VIDEO -> entry.mimeType.startsWith("video/")
                VaultFilter.AUDIO -> entry.mimeType.startsWith("audio/")
                VaultFilter.IMAGE -> entry.mimeType.startsWith("image/")
                VaultFilter.OTHER -> !entry.mimeType.startsWith("video/") &&
                    !entry.mimeType.startsWith("audio/") &&
                    !entry.mimeType.startsWith("image/")
            }
            matchesQuery && matchesType
        }
        when (activeSort) {
            VaultSort.NEWEST -> base.sortedByDescending { it.addedAtMs }
            VaultSort.NAME -> base.sortedBy { it.displayName.lowercase(Locale.getDefault()) }
            VaultSort.SIZE -> base.sortedByDescending { it.sizeBytes }
        }
    }

    val totalVaultBytes = remember(entries.toList()) {
        entries.sumOf { it.sizeBytes.coerceAtLeast(0L) }
    }

    Scaffold(
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
                    onClick = {
                        touchActivity()
                        suppressNextBackgroundLock = true
                        picker.launch(arrayOf("*/*"))
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.vault_import_files))
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
                                    formatSize(totalVaultBytes)
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
                                onClick = {
                                    touchActivity()
                                    val saved = browserTreeUri
                                    if (saved != null) openBrowserFolder(saved, null, null)
                                    else {
                                        suppressNextBackgroundLock = true
                                        browserSourcePicker.launch(null)
                                    }
                                },
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
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Box {
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.vault_sort_label))
                        }
                        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                            VaultSort.entries.forEach { sortOption ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            (if (activeSort == sortOption) "✓ " else "") +
                                                stringResource(sortOption.labelRes)
                                        )
                                    },
                                    onClick = {
                                        activeSort = sortOption
                                        showSortMenu = false
                                    }
                                )
                            }
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
                    VaultFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = activeFilter == filter,
                            onClick = {
                                touchActivity()
                                activeFilter = filter
                            },
                            label = { Text(stringResource(filter.labelRes)) }
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
                        val statusText = buildString {
                            if (importTotal > 0) append("$importProgress / $importTotal ")
                            progressDetail?.let { append(it) }
                        }.ifBlank { stringResource(R.string.vault_processing) }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }

                if (filteredEntries.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
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
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(filteredEntries, key = { it.id }) { entry ->
                            val isSelected = entry.id in selectedEntryIds
                            ElevatedCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        touchActivity()
                                        if (selectedEntryIds.isNotEmpty()) {
                                            if (isSelected) selectedEntryIds.remove(entry.id)
                                            else selectedEntryIds.add(entry.id)
                                        } else if (entry.mimeType == PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME) {
                                            pendingFolderRestore = entry
                                            suppressNextBackgroundLock = true
                                            folderRestorePicker.launch(null)
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
                                    if (selectedEntryIds.isNotEmpty()) {
                                        IconButton(
                                            onClick = {
                                                if (isSelected) selectedEntryIds.remove(entry.id)
                                                else selectedEntryIds.add(entry.id)
                                            }
                                        ) {
                                            Icon(
                                                imageVector = if (isSelected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank,
                                                contentDescription = null
                                            )
                                        }
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
                                        Text(
                                            if (entry.mimeType == PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME) {
                                                "مجلد مشفّر · ${formatSize(entry.sizeBytes)}"
                                            } else {
                                                "${entry.mimeType} · ${formatSize(entry.sizeBytes)}"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (selectedEntryIds.isEmpty()) {
                                        IconButton(
                                            onClick = {
                                                touchActivity()
                                                selectedEntryIds.add(entry.id)
                                            },
                                            enabled = !busy
                                        ) {
                                            Icon(
                                                Icons.Filled.CheckBoxOutlineBlank,
                                                contentDescription = stringResource(R.string.vault_select_item)
                                            )
                                        }
                                        TextButton(
                                            onClick = {
                                                touchActivity()
                                                if (entry.mimeType == PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME) {
                                                    pendingFolderRestore = entry
                                                    suppressNextBackgroundLock = true
                                                    folderRestorePicker.launch(null)
                                                } else {
                                                    pendingExport = entry
                                                    suppressNextBackgroundLock = true
                                                    exporter.launch(entry.displayName)
                                                }
                                            },
                                            enabled = !busy
                                        ) {
                                            Text(
                                                if (entry.mimeType == PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME) "استعادة"
                                                else "تصدير"
                                            )
                                        }
                                        IconButton(
                                            onClick = {
                                                touchActivity()
                                                pendingDelete = entry
                                            },
                                            enabled = !busy
                                        ) {
                                            Icon(Icons.Filled.Delete, contentDescription = null)
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
                                message = it.localizedMessage ?: "تعذر حذف العناصر المحددة."
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
            title = { Text("حذف من الخزنة") },
            text = { Text("سيتم حذف النسخة المشفرة من \"${entry.displayName}\" نهائياً.") },
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
                ) { Text("حذف") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("إلغاء") } }
        )
    }

    // Read-only SAF source browser dialog
    if (browserOpen && vaultSession != null) {
        val activeSession = vaultSession!!
        AlertDialog(
            onDismissRequest = { if (!busy) browserOpen = false },
            title = {
                Column {
                    Text(browserPath.lastOrNull()?.second ?: "متصفح الملفات")
                    Text(
                        "للقراءة فقط: اختر ملفات لنسخها أو نقلها إلى الخزنة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (browserPath.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    browserPath.removeAt(browserPath.lastIndex)
                                    browserTreeUri?.let { openBrowserFolder(it, browserPath.lastOrNull()?.first, null) }
                                }
                            ) { Text("رجوع") }
                        }
                        TextButton(
                            onClick = {
                                suppressNextBackgroundLock = true
                                browserSourcePicker.launch(null)
                            }
                        ) { Text("تغيير المجلد") }
                    }
                    if (browserLoading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (browserItems.isEmpty() && !browserLoading) {
                        Text("هذا المجلد فارغ أو لم يمنح النظام صلاحية قراءته.")
                    } else {
                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(browserItems, key = { it.documentId }) { item ->
                                val key = item.uri.toString()
                                val checked = key in selectedUris
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (item.isDirectory) {
                                                browserTreeUri?.let { openBrowserFolder(it, item.documentId, item.displayName) }
                                            } else {
                                                if (checked) selectedUris.remove(key) else selectedUris.add(key)
                                            }
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (!item.isDirectory) {
                                        Checkbox(
                                            checked = checked,
                                            onCheckedChange = {
                                                if (it) selectedUris.add(key) else selectedUris.remove(key)
                                            }
                                        )
                                    } else {
                                        Icon(Icons.Filled.Folder, contentDescription = null)
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (!item.isDirectory) {
                                            Text(
                                                "${item.mimeType} · ${formatSize(item.sizeBytes)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !busy && selectedUris.isNotEmpty(),
                        onClick = {
                            val chosen = browserItems.filter { it.uri.toString() in selectedUris }
                            browserOpen = false
                            scope.launch {
                                busy = true
                                importTotal = chosen.size
                                importProgress = 0
                                byteFraction = 0f
                                var copied = 0
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        chosen.forEachIndexed { idx, item ->
                                            progressDetail = item.displayName
                                            repository.importUri(
                                                item.uri, activeSession, item.displayName, item.mimeType
                                            ) { doneBytes ->
                                                if (item.sizeBytes > 0L) {
                                                    byteFraction = (doneBytes.toFloat() / item.sizeBytes.toFloat()).coerceIn(0f, 1f)
                                                }
                                            }
                                            copied++
                                            importProgress = idx + 1
                                            byteFraction = 0f
                                        }
                                    }
                                    refreshEntries(activeSession)
                                    selectedUris.clear()
                                }.onSuccess {
                                    message = "تم نسخ $copied ملف إلى الخزنة."
                                }.onFailure {
                                    message = "تم نسخ $copied من ${chosen.size}: ${it.localizedMessage}"
                                }
                                importProgress = 0
                                importTotal = 0
                                progressDetail = null
                                byteFraction = 0f
                                busy = false
                            }
                        }
                    ) { Text("نسخ إلى الخزنة") }
                    TextButton(
                        enabled = !busy && selectedUris.isNotEmpty(),
                        onClick = {
                            val chosen = browserItems.filter { it.uri.toString() in selectedUris }
                            browserOpen = false
                            scope.launch {
                                busy = true
                                importTotal = chosen.size
                                importProgress = 0
                                byteFraction = 0f
                                var moved = 0
                                val deletedIds = mutableListOf<String>()
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        chosen.forEachIndexed { idx, item ->
                                            progressDetail = item.displayName
                                            repository.importUri(
                                                item.uri, activeSession, item.displayName, item.mimeType
                                            ) { doneBytes ->
                                                if (item.sizeBytes > 0L) {
                                                    byteFraction = (doneBytes.toFloat() / item.sizeBytes.toFloat()).coerceIn(0f, 1f)
                                                }
                                            }
                                            val deleted = runCatching {
                                                DocumentsContract.deleteDocument(context.contentResolver, item.uri)
                                            }.getOrDefault(false)
                                            if (deleted) {
                                                deletedIds += PrivateVaultTreeSupport.libraryMediaId(item.uri)
                                                moved++
                                            }
                                            importProgress = idx + 1
                                            byteFraction = 0f
                                        }
                                    }
                                    if (deletedIds.isNotEmpty()) libraryViewModel.markMediaDeletedExternally(deletedIds)
                                    refreshEntries(activeSession)
                                    selectedUris.clear()
                                }.onSuccess {
                                    message = "تم نقل $moved من ${chosen.size} ملفاً إلى الخزنة."
                                }.onFailure {
                                    message = it.localizedMessage ?: "تعذر نقل بعض الملفات."
                                }
                                importProgress = 0
                                importTotal = 0
                                progressDetail = null
                                byteFraction = 0f
                                busy = false
                            }
                        }
                    ) { Text("نقل إلى الخزنة") }
                }
            },
            dismissButton = { TextButton(onClick = { browserOpen = false }) { Text("إغلاق") } }
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
            modifier = Modifier.fillMaxWidth().height(4.dp)
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

private fun formatSize(bytes: Long): String {
    if (bytes < 0L) return "غير معروف"
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
        bytes < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024f * 1024f))
        else -> String.format(Locale.US, "%.2f GB", bytes / (1024f * 1024f * 1024f))
    }
}

private tailrec fun Context.findVaultActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findVaultActivity()
    else -> null
}
