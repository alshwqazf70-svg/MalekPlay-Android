package com.malekplay.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayOutputStream
import kotlin.math.abs

private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024

internal data class AmbientArtworkPalette(
    val primaryArgb: Int,
    val secondaryArgb: Int
)

private val ambientPaletteCache = LruCache<String, AmbientArtworkPalette>(48)

internal fun loadAmbientArtworkPalette(
    context: Context,
    artworkUri: Uri?,
    mediaUri: Uri?,
    cacheKey: String
): AmbientArtworkPalette? {
    ambientPaletteCache.get(cacheKey)?.let { return it }
    val bitmap = loadMediaArtwork(context, artworkUri, mediaUri, targetSize = 96) ?: return null
    val palette = extractAmbientPalette(bitmap)
    bitmap.recycle()
    return palette?.also { ambientPaletteCache.put(cacheKey, it) }
}

/**
 * Extracts a vivid primary + complementary secondary color pair from album artwork
 * on a tiny downsampled grid so background glow adapts to each track with minimal CPU.
 */
internal fun extractAmbientPalette(bitmap: Bitmap): AmbientArtworkPalette? {
    val w = bitmap.width
    val h = bitmap.height
    if (w <= 0 || h <= 0) return null

    val stepX = maxOf(1, w / 12)
    val stepY = maxOf(1, h / 12)
    val hsv = FloatArray(3)
    var bestPrimary = 0
    var bestPrimaryScore = -1f
    var bestPrimaryHue = 0f
    var bestSecondary = 0
    var bestSecondaryScore = -1f

    var y = stepY / 2
    while (y < h) {
        var x = stepX / 2
        while (x < w) {
            val pixel = bitmap.getPixel(x, y)
            if (AndroidColor.alpha(pixel) >= 200) {
                AndroidColor.colorToHSV(pixel, hsv)
                val sat = hsv[1]
                val value = hsv[2]
                // Favor saturated, mid-to-bright pigments; avoid crushed blacks and blown whites.
                if (value in 0.16f..0.96f) {
                    val score = sat * 1.8f + (1f - abs(value - 0.58f)) * 0.6f
                    if (score > bestPrimaryScore) {
                        bestPrimaryScore = score
                        bestPrimary = pixel
                        bestPrimaryHue = hsv[0]
                    }
                }
            }
            x += stepX
        }
        y += stepY
    }
    if (bestPrimary == 0) return null

    y = stepY / 2
    while (y < h) {
        var x = stepX / 2
        while (x < w) {
            val pixel = bitmap.getPixel(x, y)
            if (AndroidColor.alpha(pixel) >= 200) {
                AndroidColor.colorToHSV(pixel, hsv)
                val hueDiff = minOf(abs(hsv[0] - bestPrimaryHue), 360f - abs(hsv[0] - bestPrimaryHue))
                if (hsv[2] in 0.16f..0.96f && hueDiff >= 28f) {
                    val score = hsv[1] * 1.5f + (hueDiff / 180f)
                    if (score > bestSecondaryScore) {
                        bestSecondaryScore = score
                        bestSecondary = pixel
                    }
                }
            }
            x += stepX
        }
        y += stepY
    }

    val normalizedPrimary = boostAmbientColor(bestPrimary)
    val normalizedSecondary = if (bestSecondary != 0) {
        boostAmbientColor(bestSecondary)
    } else {
        shiftHue(normalizedPrimary, 42f)
    }
    return AmbientArtworkPalette(normalizedPrimary, normalizedSecondary)
}

private fun boostAmbientColor(argb: Int): Int {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(argb, hsv)
    hsv[1] = hsv[1].coerceIn(0.45f, 0.92f)
    hsv[2] = hsv[2].coerceIn(0.42f, 0.88f)
    return AndroidColor.HSVToColor(255, hsv)
}

private fun shiftHue(argb: Int, degrees: Float): Int {
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(argb, hsv)
    hsv[0] = (hsv[0] + degrees) % 360f
    hsv[1] = (hsv[1] * 0.9f).coerceIn(0.40f, 0.88f)
    hsv[2] = (hsv[2] * 0.92f).coerceIn(0.38f, 0.86f)
    return AndroidColor.HSVToColor(255, hsv)
}

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

/**
 * Chooses a power-of-two decode sample from the longest edge. Checking both
 * dimensions kept very wide/tall covers almost full-resolution in memory.
 */
internal fun calculateArtworkSampleSize(width: Int, height: Int, targetSize: Int): Int {
    if (width <= 0 || height <= 0 || targetSize <= 0) return 1
    val target = targetSize
    var sample = 1
    while (sample <= Int.MAX_VALUE / 2 &&
        maxOf(width / (sample * 2), height / (sample * 2)) >= target
    ) {
        sample *= 2
    }
    return sample
}

private fun decodeBounded(bytes: ByteArray, targetSize: Int): Bitmap? {
    if (bytes.isEmpty() || bytes.size > MAX_ARTWORK_BYTES) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val sample = calculateArtworkSampleSize(
        width = bounds.outWidth,
        height = bounds.outHeight,
        targetSize = targetSize
    )

    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
    )
}
