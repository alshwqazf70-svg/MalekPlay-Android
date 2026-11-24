package com.malekplay.ui.vault

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.malekplay.vault.PrivateVaultEntry
import com.malekplay.vault.PrivateVaultRepository
import com.malekplay.vault.PrivateVaultTreeSupport
import com.malekplay.vault.VaultBrowserItem
import coil.compose.AsyncImage
import com.malekplay.ui.library.LibraryViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun PrivateVaultScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember(context) { PrivateVaultRepository(context) }
    val treeSupport = remember(context, repository) { PrivateVaultTreeSupport(context, repository) }
    val libraryViewModel: LibraryViewModel = hiltViewModel()
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var unlocked by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(repository.isConfigured()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val entries = remember { mutableStateListOf<PrivateVaultEntry>() }
    var pendingExport by remember { mutableStateOf<PrivateVaultEntry?>(null) }
    var pendingFolderRestore by remember { mutableStateOf<PrivateVaultEntry?>(null) }
    var moveFolderSelection by remember { mutableStateOf(false) }
    val vaultPrefs = remember(context) { context.getSharedPreferences("private_vault_browser", android.content.Context.MODE_PRIVATE) }
    var browserTreeUri by remember { mutableStateOf(vaultPrefs.getString("source_tree_uri", null)?.let(Uri::parse)) }
    var browserOpen by remember { mutableStateOf(false) }
    var browserLoading by remember { mutableStateOf(false) }
    val browserPath = remember { mutableStateListOf<Pair<String, String>>() }
    val browserItems = remember { mutableStateListOf<VaultBrowserItem>() }
    val selectedUris = remember { mutableStateListOf<String>() }
    var importProgress by remember { mutableIntStateOf(0) }
    var importTotal by remember { mutableIntStateOf(0) }

    fun leaveVault() {
        password = ""
        confirmation = ""
        entries.clear()
        unlocked = false
        pendingExport = null
        pendingFolderRestore = null
        onBack()
    }

    suspend fun refresh() {
        try {
            val result = withContext(Dispatchers.IO) { repository.list(password.toCharArray()) }
            entries.clear()
            entries.addAll(result)
            unlocked = true
            message = null
        } catch (failure: Exception) {
            unlocked = false
            message = failure.localizedMessage ?: "تعذر فتح الخزنة"
        }
    }

    suspend fun loadBrowserFolder(documentId: String) {
        val tree = browserTreeUri ?: return
        browserLoading = true
        try {
            val result = withContext(Dispatchers.IO) { treeSupport.listBrowserChildren(tree, documentId) }
            browserItems.clear()
            browserItems.addAll(result)
        } catch (failure: Exception) {
            message = failure.localizedMessage ?: "تعذر قراءة المجلد"
        } finally {
            browserLoading = false
        }
    }

    fun openBrowser() {
        val tree = browserTreeUri ?: return
        scope.launch {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            browserPath.clear()
            browserPath.add(rootId to "التخزين")
            selectedUris.clear()
            browserOpen = true
            loadBrowserFolder(rootId)
        }
    }

    val browserRootPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
            browserTreeUri = uri
            vaultPrefs.edit().putString("source_tree_uri", uri.toString()).apply()
            openBrowser()
        }
    }

    fun backFromBrowser() {
        if (browserPath.size > 1) {
            browserPath.removeAt(browserPath.lastIndex)
            val parentId = browserPath.last().first
            scope.launch { loadBrowserFolder(parentId) }
        } else {
            browserOpen = false
            browserItems.clear()
            selectedUris.clear()
        }
    }

    BackHandler(enabled = true) {
        if (browserOpen) backFromBrowser() else leaveVault()
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            try {
                uris.forEach { uri ->
                    runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    withContext(Dispatchers.IO) { repository.importUri(uri, password.toCharArray()) }
                }
                refresh()
            } catch (failure: Exception) { message = failure.localizedMessage ?: "تعذر استيراد الملفات" }
            finally { busy = false }
        }
    }
    val moveFilesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true
            var encryptedCount = 0
            val deletedUris = mutableListOf<String>()
            try {
                uris.forEach { uri ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    }
                    withContext(Dispatchers.IO) { repository.importUri(uri, password.toCharArray()) }
                    encryptedCount++
                    val deleted = withContext(Dispatchers.IO) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                            .getOrElse { runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false) }
                    }
                    if (deleted) deletedUris += PrivateVaultTreeSupport.libraryMediaId(uri)
                }
                message = "شُفّر $encryptedCount ملفاً؛ حُذفت أصول ${deletedUris.size} ملفاً بعد التحقق، وبقيت أصول الملفات التي رفض مزود التخزين حذفها."
            } catch (failure: Exception) {
                message = failure.localizedMessage ?: "توقفت عملية النقل؛ النسخ التي شُفرت تبقى محفوظة بالخزنة."
            } finally {
                // Reconcile even after a later item fails: earlier sources may
                // already have been deleted successfully in this same batch.
                libraryViewModel.reconcileMovedMediaUris(deletedUris)
                if (encryptedCount > 0) {
                    val operationMessage = message
                    runCatching {
                        val latest = withContext(Dispatchers.IO) { repository.list(password.toCharArray()) }
                        entries.clear()
                        entries.addAll(latest)
                        unlocked = true
                        message = operationMessage
                    }
                }
                busy = false
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                val result = withContext(Dispatchers.IO) { treeSupport.importFolder(uri, password.toCharArray(), moveSource = moveFolderSelection) }
                moveFolderSelection = false
                libraryViewModel.reconcileMovedMediaUris(result.deletedSourceUris)
                refresh()
                message = if (result.sourceFullyMoved && result.fileCount > 0) "تم تشفير المجلد ونقل ${result.deletedSourceFiles} ملفاً مع إزالة عناصر المصدر التابعة." else if (result.deletedSourceFiles > 0) "شُفّر المجلد؛ نُقلت ${result.deletedSourceFiles} من ${result.fileCount} ملفات، وبقيت بعض عناصر المصدر." else "تم تشفير المجلد مع ${result.fileCount} ملفاً؛ بقيت الأصول الأصلية أو تعذر حذفها."
            } catch (failure: Exception) { message = failure.localizedMessage ?: "تعذر استيراد المجلد" }
            finally { busy = false }
        }
    }
    val folderRestorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val entry = pendingFolderRestore; pendingFolderRestore = null
        if (uri != null && entry != null) scope.launch {
            busy = true
            try {
                runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                withContext(Dispatchers.IO) { treeSupport.restoreFolder(entry, uri, password.toCharArray()) }
                message = "تمت استعادة المجلد وبنيته الداخلية. النسخة المشفرة ما زالت محفوظة بالخزنة."
            } catch (failure: Exception) { message = failure.localizedMessage ?: "تعذرت استعادة المجلد" }
            finally { busy = false }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val entry = pendingExport; pendingExport = null
        if (uri != null && entry != null) scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { repository.exportTo(entry.id, uri, password.toCharArray()) }
                message = "تمت استعادة نسخة من الملف. النسخة المشفرة ما زالت محفوظة بالخزنة."
            } catch (failure: Exception) { message = failure.localizedMessage ?: "تعذرت الاستعادة" }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { if (browserOpen) backFromBrowser() else leaveVault() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") }
            Text("الخزنة الخاصة", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        if (browserOpen) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = { backFromBrowser() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                }
                Column(Modifier.weight(1f)) {
                    Text("اختيار ملفات للخزنة", style = MaterialTheme.typography.titleLarge)
                    Text(browserPath.joinToString("  ›  ") { it.second }, style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${selectedUris.size} محدد", color = MaterialTheme.colorScheme.primary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = {
                    selectedUris.addAll(browserItems.filterNot { it.isDirectory }.map { it.uri.toString() }.filterNot { it in selectedUris })
                }, enabled = !browserLoading && browserItems.any { !it.isDirectory }) { Text("تحديد الملفات") }
                TextButton(onClick = { selectedUris.clear() }, enabled = selectedUris.isNotEmpty()) { Text("إلغاء التحديد") }
            }
            if (browserLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else if (browserItems.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("هذا المجلد فارغ أو لا يحتوي على عناصر يمكن عرضها.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(browserItems, key = { it.documentId }) { item ->
                        val checked = selectedUris.contains(item.uri.toString())
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                    if (item.isDirectory) {
                                        browserPath.add(item.documentId to item.displayName)
                                        scope.launch { loadBrowserFolder(item.documentId) }
                                    } else {
                                        if (checked) selectedUris.remove(item.uri.toString())
                                        else selectedUris.add(item.uri.toString())
                                    }
                                }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (item.isDirectory) {
                                    Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(48.dp))
                                } else if (item.mimeType.startsWith("image/") || item.mimeType.startsWith("video/")) {
                                    AsyncImage(model = item.uri, contentDescription = null,
                                        modifier = Modifier.size(width = 76.dp, height = 58.dp), contentScale = ContentScale.Crop)
                                } else {
                                    Icon(Icons.Filled.InsertDriveFile, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(48.dp))
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(item.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall)
                                    Text(if (item.isDirectory) "مجلد" else formatBytes(item.sizeBytes),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
                                if (!item.isDirectory) {
                                    Checkbox(checked = checked, onCheckedChange = { value ->
                                        if (value) selectedUris.add(item.uri.toString())
                                        else selectedUris.remove(item.uri.toString())
                                    })
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "فتح المجلد",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            Button(
                onClick = {
                    val chosen = selectedUris.toList().map(Uri::parse)
                    if (chosen.isNotEmpty()) scope.launch {
                        busy = true
                        browserOpen = false
                        importTotal = chosen.size
                        importProgress = 0
                        var completed = 0
                        val failures = mutableListOf<String>()
                        try {
                            chosen.forEach { uri ->
                                try {
                                    withContext(Dispatchers.IO) { repository.importUri(uri, password.toCharArray()) }
                                    completed++
                                } catch (failure: Exception) {
                                    failures += (failure.localizedMessage ?: "تعذر تشفير الملف")
                                } finally { importProgress++ }
                            }
                            refresh()
                            message = if (failures.isEmpty()) "تم تشفير وإضافة $completed ملفاً إلى الخزنة." else
                                "تم حفظ $completed ملفاً، وتعذر حفظ ${failures.size}. ${failures.first()}"
                            selectedUris.clear()
                        } finally { busy = false }
                    }
                },
                enabled = !busy && !browserLoading && selectedUris.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary)
            ) { Text("إضافة الآن (${selectedUris.size})") }
        } else if (!unlocked) {
            Text(if (configured) "أدخل كلمة المرور لفتح ملفاتك الخاصة." else "أنشئ كلمة مرور من 8 أحرف على الأقل. سيُشفّر محتوى الملفات وفهرسها داخل مساحة التطبيق.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("كلمة المرور") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            if (!configured) OutlinedTextField(confirmation, { confirmation = it }, modifier = Modifier.fillMaxWidth(), label = { Text("تأكيد كلمة المرور") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Button(
                enabled = !busy && password.length >= 8 && (configured || password == confirmation),
                onClick = {
                    scope.launch {
                        busy = true
                        try {
                            if (!configured) { withContext(Dispatchers.IO) { repository.create(password.toCharArray()) }; configured = true }
                            refresh()
                        } catch (failure: Exception) { message = failure.localizedMessage ?: "تعذر إعداد الخزنة" }
                        finally { busy = false }
                    }
                }, modifier = Modifier.fillMaxWidth()
            ) { Text(if (configured) "فتح الخزنة" else "إنشاء الخزنة") }
            Text("إذا فُقدت كلمة المرور فلن يمكن فك تشفير الملفات. احتفظ بها في مكان آمن.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Text(entries.size.toString() + " عنصر مشفّر", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { if (browserTreeUri == null) browserRootPicker.launch(null) else openBrowser() },
                        enabled = !busy,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary)
                    ) { Icon(Icons.Filled.Add, contentDescription = null); Text("إضافة ملفات") }
                    TextButton(onClick = { moveFilesPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Text("نقل ملفات") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { moveFolderSelection = false; folderPicker.launch(null) }, enabled = !busy) { Text("نسخ مجلد") }
                    TextButton(onClick = { moveFolderSelection = true; folderPicker.launch(null) }, enabled = !busy) { Text("نقل مجلد") }
                }
            }
            Text("النقل لا يحذف الأصول إلا بعد نجاح التشفير؛ وإذا رفض مزود الملفات الحذف فتبقى الأصول الأصلية.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.id }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(entry.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(entry.mimeType + " • " + formatBytes(entry.sizeBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (entry.mimeType == PrivateVaultTreeSupport.FOLDER_ARCHIVE_MIME) {
                                TextButton(onClick = { pendingFolderRestore = entry; folderRestorePicker.launch(null) }, enabled = !busy) { Text("استعادة مجلد") }
                            } else {
                                TextButton(onClick = { pendingExport = entry; exportPicker.launch(entry.displayName) }, enabled = !busy) { Text("استعادة") }
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        withContext(Dispatchers.IO) { repository.delete(entry.id, password.toCharArray()) }
                                        entries.remove(entry); message = "حُذفت النسخة المشفّرة من الخزنة."
                                    } catch (failure: Exception) { message = failure.localizedMessage ?: "تعذر الحذف" }
                                    finally { busy = false }
                                }
                            }, enabled = !busy) { Icon(Icons.Filled.Delete, contentDescription = "حذف من الخزنة") }
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { password = ""; confirmation = ""; entries.clear(); unlocked = false; pendingExport = null; pendingFolderRestore = null }, modifier = Modifier.align(Alignment.End)) { Text("قفل الخزنة") }
        }
        if (busy) {
            Text(if (importTotal > 0) "جارٍ تشفير الملفات: $importProgress / $importTotal" else "جارٍ تنفيذ العملية…",
                color = MaterialTheme.colorScheme.primary)
            if (importTotal > 0) LinearProgressIndicator(
                progress = { importProgress.toFloat() / importTotal.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
