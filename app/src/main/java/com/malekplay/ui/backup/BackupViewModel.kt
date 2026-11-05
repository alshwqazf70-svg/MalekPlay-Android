package com.malekplay.ui.backup

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.malekplay.backup.MalekPlayBackupManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backupManager: MalekPlayBackupManager
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun export(uri: Uri) = runBackup("جارٍ إنشاء النسخة الاحتياطية...") {
        backupManager.exportTo(uri)
        "تم تصدير الإعدادات والمفضلة وقوائم التشغيل"
    }

    fun restore(uri: Uri) = runBackup("جارٍ التحقق من النسخة واستعادتها...") {
        backupManager.restoreFrom(uri)
        "تمت استعادة الإعدادات والمفضلة وقوائم التشغيل"
    }

    private fun runBackup(startMessage: String, action: suspend () -> String) {
        viewModelScope.launch {
            _busy.value = true
            _message.value = startMessage
            try {
                _message.value = withContext(Dispatchers.IO) { action() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _message.value = "تعذرت العملية: " + (error.localizedMessage ?: "خطأ غير معروف")
            } finally {
                _busy.value = false
            }
        }
    }
}
