package com.malekplay.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import okio.source
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

object UltraShareTransport {
    private const val TAG = "UltraShareTransport"
    const val CHUNK_BYTES = 64 * 1024 // 64KB (مثالي للسرعة)
    const val DEFAULT_PORT = 8089

    data class Progress(
        val fileName: String,
        val transferredBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long = 0L
    ) {
        val fraction: Float get() = if (totalBytes > 0) transferredBytes.toFloat() / totalBytes else 0f
    }

    data class ReceivedFile(
        val fileName: String,
        val sizeBytes: Long,
        val file: File,
        val mimeType: String,
        val publishedUri: String? = null
    )

    // --- دوال الأمان والاقتران ---
    fun createPairingSecret(): String {
        val random = SecureRandom()
        return String.format("%09d", random.nextInt(1000000000))
    }

    fun isValidPairingSecret(secret: String): Boolean {
        return secret.length == 9 && secret.all { it.isDigit() }
    }

    // ⚡️ دالة تحسين الـ Socket للسرعة القصوى
    private fun optimizeSocket(socket: Socket) {
        try {
            socket.tcpNoDelay = true // منع التأخير
            socket.sendBufferSize = CHUNK_BYTES // 64KB
            socket.receiveBufferSize = CHUNK_BYTES // 64KB
            socket.keepAlive = true
        } catch (e: Exception) {
            Log.e(TAG, "فشل تحسين إعدادات الـ Socket", e)
        }
    }

    // --- دوال الإرسال (Sender) ---
    suspend fun sendFile(
        host: String,
        port: Int,
        file: File,
        onProgress: (Progress) -> Unit
    ) = withContext(Dispatchers.IO) {
        val socket = Socket(host, port)
        optimizeSocket(socket) // ⚡️ تطبيق التحسينات
        
        try {
            val totalBytes = file.length()
            var transferredBytes = 0L
            val startTime = System.currentTimeMillis()

            // استخدام Okio للإرسال السريع
            val sink = socket.getOutputStream().sink().buffer()
            file.source().use { source ->
                val buffer = okio.Buffer()
                var read: Long
                while (source.read(buffer, CHUNK_BYTES.toLong()).also { read = it } != -1L) {
                    sink.write(buffer, read)
                    transferredBytes += read
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    val speed = if (elapsed > 0) (transferredBytes * 1000 / elapsed) else 0L
                    
                    onProgress(Progress(file.name, transferredBytes, totalBytes, speed))
                }
            }
            sink.flush()
            Log.d(TAG, "تم إرسال الملف بنجاح: ${file.name}")
        } finally {
            socket.close()
        }
    }

    // --- دوال الاستقبال (Receiver) ---
    suspend fun receiveFile(
        serverSocket: ServerSocket,
        targetDirectory: File,
        expectedFileName: String,
        expectedSize: Long,
        onProgress: (Progress) -> Unit
    ): ReceivedFile? = withContext(Dispatchers.IO) {
        try {
            val socket = serverSocket.accept()
            optimizeSocket(socket) // ⚡️ تطبيق التحسينات
            
            val targetFile = File(targetDirectory, expectedFileName)
            targetDirectory.mkdirs()

            var transferredBytes = 0L
            val startTime = System.currentTimeMillis()

            // استخدام Okio للاستقبال السريع
            val source = socket.getInputStream().source().buffer()
            targetFile.sink().buffer().use { sink ->
                val buffer = okio.Buffer()
                var read: Long
                while (source.read(buffer, CHUNK_BYTES.toLong()).also { read = it } != -1L) {
                    sink.write(buffer, read)
                    transferredBytes += read
                    
                    val elapsed = System.currentTimeMillis() - startTime
                    val speed = if (elapsed > 0) (transferredBytes * 1000 / elapsed) else 0L
                    
                    onProgress(Progress(expectedFileName, transferredBytes, expectedSize, speed))
                }
            }

            socket.close()
            Log.d(TAG, "تم استقبال الملف بنجاح: ${targetFile.name}")

            ReceivedFile(
                fileName = targetFile.name,
                sizeBytes = transferredBytes,
                file = targetFile,
                mimeType = "application/octet-stream" // يمكنك تحسين هذا لاحقاً بناءً على الامتداد
            )
        } catch (e: Exception) {
            Log.e(TAG, "فشل استقبال الملف", e)
            null
        }
    }
}
