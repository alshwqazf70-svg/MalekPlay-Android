package com.malekplay.network

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Copies a user-selected content:// document into an app-private, stable file
 * before handing it to native NSTACKX DFile (which accepts filesystem paths).
 *
 * Staging is deliberately independent from DFile session creation so a failed
 * native start can safely fall back to the existing URI-based TCP transport.
 */
internal object UltraShareDFileStager {
    private const val BUFFER_BYTES = 256 * 1024
    private const val STAGING_DIRECTORY = "ultrashare-dfile-staging"

    data class StagedFile(
        val file: File,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val sha256: ByteArray,
        private val stagingDirectory: File
    ) : AutoCloseable {
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { file.delete() }
            runCatching { stagingDirectory.delete() }
            sha256.fill(0)
        }
    }

    suspend fun stage(
        resolver: ContentResolver,
        cacheDirectory: File,
        uri: Uri,
        displayName: String,
        mimeType: String,
        expectedSizeBytes: Long
    ): StagedFile = withContext(Dispatchers.IO) {
        require(expectedSizeBytes in 0..UltraShareTransport.MAX_FILE_BYTES) {
            "حجم الملف غير مدعوم."
        }
        val safeName = UltraShareTransport.sanitizeFileName(displayName)
        val safeMime = UltraShareTransport.sanitizeMimeType(mimeType)
        require(UltraShareTransport.isAllowedShareFile(safeName, safeMime)) {
            "لا يمكن مشاركة ملفات APK أو PDF."
        }

        val root = File(cacheDirectory, STAGING_DIRECTORY)
        check(root.exists() || root.mkdirs()) { "تعذر إنشاء مساحة تجهيز الملفات." }
        check(root.isDirectory && root.canonicalFile.parentFile == cacheDirectory.canonicalFile) {
            "مسار تجهيز الملفات غير آمن."
        }

        // Keep the original sanitized filename: NSTACKX DFile derives the
        // remote filename from the staged path's leaf name.
        val stagingDirectory = File(root, "session-" + java.util.UUID.randomUUID().toString())
        check(stagingDirectory.mkdir()) { "تعذر حجز مساحة مؤقتة للملف." }
        val target = File(stagingDirectory, safeName)
        var completed = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            val input = resolver.openInputStream(uri)
                ?: error("تعذر فتح الملف المحدد.")
            input.use { source ->
                FileOutputStream(target, false).use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        if (count == 0) {
                            val single = source.read()
                            if (single < 0) break
                            check(copied < expectedSizeBytes) { "تجاوز الملف الحجم المعلن." }
                            output.write(single)
                            digest.update(single.toByte())
                            copied++
                        } else {
                            check(copied <= expectedSizeBytes - count) {
                                "تجاوز الملف الحجم المعلن."
                            }
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                        }
                    }
                    output.fd.sync()
                }
            }
            check(copied == expectedSizeBytes && target.length() == expectedSizeBytes) {
                "تغير حجم الملف أثناء تجهيزه؛ أعد اختيار الملف."
            }
            check(isSafeStagedFile(
                target = target,
                stagingDirectory = stagingDirectory,
                stagingRoot = root,
                cacheDirectory = cacheDirectory,
                copiedBytes = copied,
                expectedBytes = expectedSizeBytes
            )) {
                "تعذر التحقق من مسار الملف المجهز أو حجمه."
            }
            completed = true
            StagedFile(target, safeName, safeMime, copied, digest.digest(), stagingDirectory)
        } finally {
            if (!completed) {
                runCatching { target.delete() }
                runCatching { stagingDirectory.delete() }
            }
        }
    }

    internal fun isSafeStagedFile(
        target: File,
        stagingDirectory: File,
        stagingRoot: File,
        cacheDirectory: File,
        copiedBytes: Long,
        expectedBytes: Long
    ): Boolean = runCatching {
        val canonicalCache = cacheDirectory.canonicalFile
        val canonicalRoot = stagingRoot.canonicalFile
        val canonicalSession = stagingDirectory.canonicalFile
        val canonicalTarget = target.canonicalFile
        copiedBytes == expectedBytes &&
            expectedBytes >= 0L &&
            canonicalRoot.parentFile == canonicalCache &&
            canonicalSession.parentFile == canonicalRoot &&
            canonicalTarget.parentFile == canonicalSession &&
            canonicalTarget.isFile &&
            canonicalTarget.length() == expectedBytes
    }.getOrDefault(false)

    /** Validates a native DFile receive result before publishing it outside staging. */
    internal fun isSafeReceivedFile(
        file: File,
        stagingDirectory: File,
        reportedBytes: Long,
        maximumBytes: Long = UltraShareTransport.MAX_FILE_BYTES
    ): Boolean = runCatching {
        val canonicalDirectory = stagingDirectory.canonicalFile
        val canonicalFile = file.canonicalFile
        val actualBytes = canonicalFile.length()
        canonicalFile.parentFile == canonicalDirectory &&
            canonicalFile.isFile &&
            actualBytes in 0..maximumBytes &&
            (reportedBytes <= 0L || reportedBytes == actualBytes)
    }.getOrDefault(false)

    /** Re-checks the staged source bytes before declaring native DFile send complete. */
    internal fun verifyStagedDigest(staged: StagedFile): Boolean = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        staged.file.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        MessageDigest.isEqual(digest.digest(), staged.sha256) &&
            staged.file.length() == staged.sizeBytes
    }.getOrDefault(false)

    fun cleanupStale(cacheDirectory: File, olderThanMillis: Long = 24L * 60L * 60L * 1000L) {
        require(olderThanMillis >= 0L)
        val root = File(cacheDirectory, STAGING_DIRECTORY)
        val cutoff = System.currentTimeMillis() - olderThanMillis
        val canonicalCache = runCatching { cacheDirectory.canonicalFile }.getOrNull() ?: return
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return
        // Never follow a replaced staging-root symlink outside the app cache.
        if (canonicalRoot.parentFile != canonicalCache) return
        root.listFiles()?.forEach { entry ->
            val isOwnedChild = runCatching {
                entry.canonicalFile.parentFile == canonicalRoot
            }.getOrDefault(false)
            if (isOwnedChild && entry.lastModified() < cutoff) {
                // Only remove children of our dedicated staging root. A malformed
                // symlink/canonical-path failure is ignored rather than aborting
                // cleanup for every other stale session directory.
                runCatching { entry.deleteRecursively() }
            }
        }
        if (root.isDirectory && root.listFiles()?.isEmpty() == true) runCatching { root.delete() }
    }
}
