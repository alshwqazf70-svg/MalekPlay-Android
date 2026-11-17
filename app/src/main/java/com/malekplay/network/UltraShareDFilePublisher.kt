package com.malekplay.network

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Publishes a fully received and already verified DFile payload without
 * replacing an existing user file.
 *
 * Publication is serialized process-wide. The temporary copy stays inside the
 * per-session staging directory so a process death cannot leave a .part file
 * in the visible receive root; age-based session cleanup removes it later.
 */
internal object UltraShareDFilePublisher {
    @Synchronized
    internal fun publish(staged: File, destination: File, safeName: String): File? =
        runCatching {
            val safeDestination = destination.canonicalFile
            val safeStaged = staged.canonicalFile
            require(safeDestination.isDirectory)
            require(safeName.isNotBlank())
            require(safeName == UltraShareTransport.sanitizeFileName(safeName))
            require('/' !in safeName && 92.toChar() !in safeName)
            require(safeStaged.isFile)
            val stagingDirectory = safeStaged.parentFile
                ?: error("DFile staging directory is missing.")
            require(stagingDirectory.parentFile == safeDestination) {
                "Staged file is outside the DFile receive session directory."
            }
            require(safeStaged.length() <= UltraShareTransport.MAX_FILE_BYTES)

            val extension = safeName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            val baseName = if (extension == null) safeName else safeName.removeSuffix(".$extension")
            val temporary = File(
                stagingDirectory,
                ".${safeName}.${UUID.randomUUID()}.part"
            )
            try {
                FileOutputStream(temporary, false).use { output ->
                    safeStaged.inputStream().use { input ->
                        input.copyTo(output, UltraShareTransport.CHUNK_BYTES)
                    }
                    output.fd.sync()
                }
                require(temporary.isFile && temporary.length() == safeStaged.length())

                var target = File(safeDestination, safeName)
                var suffix = 1
                while (target.exists()) {
                    val candidateName = if (extension == null) "$baseName ($suffix)"
                        else "$baseName ($suffix).$extension"
                    target = File(safeDestination, candidateName)
                    suffix++
                    check(suffix < MAX_NAME_ATTEMPTS) {
                        "تعذر إيجاد اسم ملف متاح دون استبدال ملف موجود."
                    }
                }

                // link(2) creates the destination atomically and fails with EEXIST;
                // unlike File.renameTo(), it cannot replace a file created between
                // the existence check and publication. Both paths are on the same
                // app-private filesystem because the temporary lives under destination.
                check(!target.exists()) {
                    "تغير اسم الوجهة أثناء نشر ملف DFile."
                }
                Os.link(temporary.absolutePath, target.absolutePath)
                check(target.canonicalFile.parentFile == safeDestination)
                check(target.isFile && target.length() == safeStaged.length())
                // Publication is already complete and verified. A failed cache
                // cleanup must not report the delivered file as failed.
                runCatching { safeStaged.delete() }
                runCatching { stagingDirectory.delete() }
                target.canonicalFile
            } finally {
                runCatching { temporary.delete() }
            }
        }.getOrNull()

    private const val MAX_NAME_ATTEMPTS = 10_000
}
