package com.malekplay.network

import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Publishes a fully received and already verified DFile payload without ever
 * selecting an existing user file as the destination.
 *
 * A destination is reserved with createNewFile() before rename. This closes
 * the exists()-then-rename() race that could otherwise replace a file created
 * by another concurrent receive operation.
 */
internal object UltraShareDFilePublisher {
    internal fun publish(staged: File, destination: File, safeName: String): File? =
        runCatching {
            val safeDestination = destination.canonicalFile
            val safeStaged = staged.canonicalFile
            require(safeDestination.isDirectory)
            require(safeName.isNotBlank())
            require(safeName == UltraShareTransport.sanitizeFileName(safeName))
            require('/' !in safeName && 92.toChar() !in safeName)
            require(safeStaged.isFile)
            require(safeStaged.parentFile?.parentFile == safeDestination) {
                "Staged file is outside the DFile receive session directory."
            }
            require(safeStaged.length() <= UltraShareTransport.MAX_FILE_BYTES)

            val extension = safeName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
            val baseName = if (extension == null) safeName else safeName.removeSuffix(".$extension")
            val temporary = File(
                safeDestination,
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

                for (suffix in 0 until MAX_NAME_ATTEMPTS) {
                    val candidateName = when {
                        suffix == 0 -> safeName
                        extension == null -> "$baseName ($suffix)"
                        else -> "$baseName ($suffix).$extension"
                    }
                    val target = File(safeDestination, candidateName)
                    // createNewFile is an exclusive reservation: false means
                    // somebody already owns this name, so try the next suffix.
                    if (!target.createNewFile()) continue

                    var reservationOwned = true
                    try {
                        check(temporary.renameTo(target)) {
                            "تعذر تثبيت الملف المستلم بأمان."
                        }
                        reservationOwned = false
                        check(target.canonicalFile.parentFile == safeDestination)
                        check(target.isFile && target.length() == safeStaged.length())
                        // Publication is already complete and verified. A failed
                        // cache cleanup must not report the delivered file as failed.
                        runCatching { safeStaged.delete() }
                        return@runCatching target.canonicalFile
                    } finally {
                        if (reservationOwned) runCatching { target.delete() }
                    }
                }
                error("تعذر إيجاد اسم ملف متاح دون استبدال ملف موجود.")
            } finally {
                runCatching { temporary.delete() }
            }
        }.getOrNull()

    private const val MAX_NAME_ATTEMPTS = 10_000
}
