package com.malekplay.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.ByteArrayOutputStream

private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024

/**
 * Loads album art from the MediaStore album-art provider first, then falls
 * back to the embedded picture stored inside the audio file (ID3/APIC, etc.).
 * BitmapFactory is used instead of ImageDecoder so this also works on API 23–27.
 */
internal fun loadMediaArtwork(
    context: Context,
    artworkUri: Uri?,
    mediaUri: Uri?,
    targetSize: Int = 256
): Bitmap? {
    artworkUri?.let { uri ->
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                decodeBounded(stream.readLimitedBytes(), targetSize)
            }
        }.getOrNull()?.let { return it }
    }

    mediaUri?.let { uri ->
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val embedded = retriever.embeddedPicture
            if (embedded != null && embedded.size <= MAX_ARTWORK_BYTES) {
                decodeBounded(embedded, targetSize)?.let { return it }
            }
        } catch (_: Exception) {
            // Unsupported container/codec or provider failure: keep the placeholder.
        } finally {
            runCatching { retriever.release() }
        }
    }
    return null
}

internal fun java.io.InputStream.readLimitedBytes(maxBytes: Int = MAX_ARTWORK_BYTES): ByteArray {
    require(maxBytes > 0) { "Maximum artwork size must be positive" }
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) {
            // A broken ContentProvider must not trap thumbnail loading in a busy loop.
            val single = read()
            if (single < 0) break
            total++
            require(total <= maxBytes) { "Album artwork exceeds size limit" }
            output.write(single)
            continue
        }
        total += read
        require(total <= maxBytes) { "Album artwork exceeds size limit" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun decodeBounded(bytes: ByteArray, targetSize: Int): Bitmap? {
    if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sample = 1
    val target = targetSize.coerceAtLeast(1)
    while (bounds.outWidth / (sample * 2) >= target &&
        bounds.outHeight / (sample * 2) >= target
    ) sample *= 2

    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    )
}
