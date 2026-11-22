package com.malekplay.ui.components

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.malekplay.domain.model.MediaItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared bulk-delete action for every library surface.
 *
 * Android 11+ supports one system confirmation for a MediaStore batch.
 * Android 10 exposes recoverable approval per protected URI, so process the
 * selection sequentially and retry only the URI whose approval was granted.
 */
@Composable
fun MediaBulkDeleteBar(
    mediaItems: List<MediaItem>,
    onDeleted: () -> Unit,
    onClearSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val resolver = context.contentResolver
    val scope = rememberCoroutineScope()
    var showConfirmation by remember { mutableStateOf(false) }
    var pendingApproval by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    var pendingPermission by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }

    val approvalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        pendingApproval?.let { deferred ->
            if (!deferred.isCompleted) deferred.complete(result.resultCode == Activity.RESULT_OK)
        }
        pendingApproval = null
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingPermission?.let { deferred ->
                if (!deferred.isCompleted) deferred.complete(granted)
            }
            pendingPermission = null
        } else {
            pendingPermission?.let { deferred ->
                if (!deferred.isCompleted) deferred.complete(false)
            }
            pendingPermission = null
        }
    }

    if (mediaItems.isEmpty()) return

    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${mediaItems.size} ملف محدد",
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleSmall
            )
            TextButton(onClick = onClearSelection) { Text("إلغاء") }
            TextButton(onClick = { showConfirmation = true }) {
                Text("حذف المحدد", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (showConfirmation) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("حذف الملفات المحددة؟") },
            text = {
                Text("سيطلب Android الموافقة اللازمة لحذف ${mediaItems.size} ملف. في Android 10 قد تظهر موافقة منفصلة لبعض الملفات المحمية.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    val uris = mediaItems.map { it.uri }.distinct()
                    if (uris.size != mediaItems.size || uris.any { it.scheme != "content" || it.authority != "media" }) {
                        Toast.makeText(context, "تعذر الحذف: بعض العناصر ليست ملفات MediaStore", Toast.LENGTH_LONG).show()
                    } else {
                        scope.launch {
                            var deletedCount = 0
                            var failedCount = 0
                            try {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    val request = MediaStore.createDeleteRequest(resolver, uris)
                                    val deferred = CompletableDeferred<Boolean>()
                                    pendingApproval = deferred
                                    approvalLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                                    val approved = deferred.await()
                                    if (approved) {
                                        val remaining = withContext(Dispatchers.IO) {
                                            uris.count { uri ->
                                                resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
                                                    it.moveToFirst()
                                                } ?: false
                                            }
                                        }
                                        deletedCount = uris.size - remaining
                                        failedCount = remaining
                                    } else {
                                        failedCount = uris.size
                                    }
                                } else {
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                                    ) {
                                        val permissionResult = CompletableDeferred<Boolean>()
                                        pendingPermission = permissionResult
                                        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        if (!permissionResult.await()) {
                                            failedCount = uris.size
                                        }
                                    }

                                    if (failedCount == 0) {
                                        for (uri in uris) {
                                            var itemDeleted = false
                                            try {
                                                try {
                                                    withContext(Dispatchers.IO) { resolver.delete(uri, null, null) }
                                                } catch (security: SecurityException) {
                                                    val recoverable = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                                                        security as? RecoverableSecurityException
                                                    } else null
                                                    if (recoverable == null) throw security

                                                    val deferred = CompletableDeferred<Boolean>()
                                                    pendingApproval = deferred
                                                    approvalLauncher.launch(
                                                        IntentSenderRequest.Builder(
                                                            recoverable.userAction.actionIntent.intentSender
                                                        ).build()
                                                    )
                                                    if (!deferred.await()) throw SecurityException("رفض المستخدم حذف هذا الملف")
                                                    // Retry only this URI after its own approval; never restart the batch.
                                                    withContext(Dispatchers.IO) { resolver.delete(uri, null, null) }
                                                }
                                                itemDeleted = withContext(Dispatchers.IO) {
                                                    resolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use {
                                                        !it.moveToFirst()
                                                    } ?: false
                                                }
                                            } catch (_: Exception) {
                                                itemDeleted = false
                                            }
                                            if (itemDeleted) deletedCount++ else failedCount++
                                        }
                                    }
                                }
                            } catch (error: Exception) {
                                failedCount = (uris.size - deletedCount).coerceAtLeast(0)
                                Toast.makeText(context, error.localizedMessage ?: "تعذر إكمال الحذف", Toast.LENGTH_LONG).show()
                            } finally {
                                pendingApproval = null
                                pendingPermission = null
                            }

                            if (deletedCount > 0) onDeleted()
                            if (failedCount == 0 && deletedCount > 0) {
                                Toast.makeText(context, "تم حذف جميع الملفات المحددة ($deletedCount)", Toast.LENGTH_LONG).show()
                                onClearSelection()
                            } else if (deletedCount > 0) {
                                Toast.makeText(context, "تم حذف $deletedCount ملف، وتعذر حذف $failedCount ملف. بقيت الملفات غير المحذوفة محددة.", Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, "لم يُحذف أي ملف. بقيت العناصر محددة لتتمكن من إعادة المحاولة.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }) {
                    Text("تأكيد الحذف", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text("تراجع") }
            }
        )
    }
}

