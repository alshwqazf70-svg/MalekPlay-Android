package com.malekplay.ui.vault

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.malekplay.vault.PrivateVaultEntry
import com.malekplay.vault.PrivateVaultRepository
import com.malekplay.vault.PrivateVaultTreeSupport
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

    fun leaveVault() {
        password = ""
        confirmation = ""
        entries.clear()
        unlocked = false
        pendingExport = null
        pendingFolderRestore = null
        onBack()
    }

    BackHandler(enabled = true) { leaveVault() }

    fun refresh() {
        scope.launch {
            busy = true
            try {
                val result = withContext(Dispatchers.IO) { repository.list(password.toCharArray()) }
                entries.clear(); entries.addAll(result); unlocked = true; message = null
            } catch (failure: Exception) {
                unlocked = false; message = failure.localizedMessage ?: "تعذر فتح الخزنة"
            } finally { busy = false }
        }
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
            IconButton(onClick = { leaveVault() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع") }
            Text("الخزنة الخاصة", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
        if (!unlocked) {
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
                    Button(onClick = { importPicker.launch(arrayOf("*/*")) }, enabled = !busy) { Icon(Icons.Filled.Add, contentDescription = null); Text("نسخ ملفات") }
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
        if (busy) Text("جارٍ تنفيذ العملية…", color = MaterialTheme.colorScheme.primary)
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> bytes.toString() + " B"
    bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
}
