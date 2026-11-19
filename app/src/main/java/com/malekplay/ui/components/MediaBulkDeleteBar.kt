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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared bulk-delete action for every library surface.
 *
 * Android 11+ presents one system confirmation for the complete MediaStore batch.
 * Android 10 may require one recoverable approval per protected item; older
 * Android versions use the legacy storage permission.
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
    var platformDeleteApproved by remember { mutableStateOf(false) }
    var deleteLegacyBatch: () -> Unit = {}

    val approvalLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (platformDeleteApproved) {
                platformDeleteApproved = false
                Toast.makeText(context, "تم حذف الملفات المحددة من الجهاز", Toast.LENGTH_LONG).show()
                onClearSelection()
                onDeleted()
            } else {
                deleteLegacyBatch()
            }
        } else {
            platformDeleteApproved = false
            Toast.makeText(context, "لم تتم الموافقة على حذف جميع الملفات", Toast.LENGTH_LONG).show()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) deleteLegacyBatch()
        else Toast.makeText(context, "يلزم السماح بحذف الملفات من مساحة التخزين", Toast.LENGTH_LONG).show()
    }

    deleteLegacyBatch = {
        scope.launch {
            try {
                val deletedCount = withContext(Dispatchers.IO) {
                    var count = 0
                    mediaItems.forEach { media ->
                        if (media.uri.scheme != "content" || media.uri.authority != "media") {
                            throw IllegalArgumentException("يوجد ملف لا ينتمي إلى مكتبة وسائط الهاتف")
                        }
                        count += resolver.delete(media.uri, null, null).coerceAtLeast(0)
                    }
                    count
                }
                if (deletedCount > 0) {
                    Toast.makeText(context, "تم حذف الملفات المحددة من الجهاز ($deletedCount ملف)", Toast.LENGTH_LONG).show()
                    onClearSelection()
                    onDeleted()
                } else {
                    Toast.makeText(context, "لم يُحذف أي ملف؛ ربما حُذفت الملفات مسبقاً", Toast.LENGTH_LONG).show()
                }
            } catch (security: RecoverableSecurityException) {
                if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                    approvalLauncher.launch(
                        IntentSenderRequest.Builder(security.userAction.actionIntent.intentSender).build()
                    )
                } else {
                    Toast.makeText(context, "لا يملك التطبيق إذن حذف بعض الملفات", Toast.LENGTH_LONG).show()
                }
            } catch (error: Exception) {
                Toast.makeText(
                    context,
                    error.localizedMessage ?: "تعذر حذف الملفات المحددة",
                    Toast.LENGTH_LONG
                ).show()
            }
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
                Text("سيتم طلب حذف ${mediaItems.size} ملف من مساحة تخزين الهاتف. الملفات التي يؤكد Android حذفها ستُزال من الجهاز ولن تعود إلى المكتبة بعد إعادة الفحص.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showConfirmation = false
                    val uris = mediaItems.map { it.uri }.distinct()
                    if (uris.size != mediaItems.size || uris.any { it.scheme != "content" || it.authority != "media" }) {
                        Toast.makeText(context, "تعذر الحذف الجماعي: بعض العناصر ليست ملفات MediaStore", Toast.LENGTH_LONG).show()
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            val request = MediaStore.createDeleteRequest(resolver, uris)
                            platformDeleteApproved = true
                            approvalLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        } catch (error: Exception) {
                            platformDeleteApproved = false
                            Toast.makeText(context, error.localizedMessage ?: "تعذر طلب إذن الحذف", Toast.LENGTH_LONG).show()
                        }
                    } else if (
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        deleteLegacyBatch()
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
