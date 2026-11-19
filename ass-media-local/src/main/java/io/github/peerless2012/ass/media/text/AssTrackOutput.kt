package io.github.peerless2012.ass.media.text

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor
import java.util.regex.Pattern

/**
 * Captures ASS dialogue timing for the overlay renderer while forwarding the original samples.
 */
@UnstableApi
class AssTrackOutput(
    private val delegate: TrackOutput,
    private val assHandler: AssHandler,
    private val extractor: AssMatroskaExtractor,
) : TrackOutput by delegate {

    private var isAss = false
    private var trackId: String? = null

    override fun format(format: Format) {
        isAss = format.sampleMimeType == MimeTypes.TEXT_SSA || format.codecs == MimeTypes.TEXT_SSA
        trackId = if (isAss) format.id else null
        delegate.format(format)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (isAss && timeUs != C.TIME_UNSET && size > 0) {
            val sample = extractor.subtitleSample
            val sampleLimit = sample.limit().coerceIn(0, sample.data.size)
            val endIndex = findTokenIndex(sample.data, sampleLimit, 1)
            val lineIndex = findTokenIndex(sample.data, sampleLimit, 2)

            // Missing commas and truncated dialogue packets are malformed input, not fatal errors.
            if (endIndex > 0 && lineIndex > endIndex && lineIndex <= sampleLimit) {
                val rawDuration = sample.data.decodeToString(endIndex, lineIndex - 1)
                val durationUs = parseTimecodeUs(rawDuration)
                val dialogueLength = sampleLimit - lineIndex
                if (durationUs > 0 && dialogueLength > 0) {
                    assHandler.readTrackDialogue(
                        trackId = trackId,
                        start = timeUs / 1000,
                        duration = durationUs / 1000,
                        data = sample.data,
                        offset = lineIndex,
                        length = dialogueLength
                    )
                }
            }
        }
        // Always pass the original sample metadata to Media3, even if ASS timing was malformed.
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun parseTimecodeUs(timeString: String): Long {
        val matcher = SSA_TIMECODE_PATTERN.matcher(timeString.trim { it <= ' ' })
        if (!matcher.matches()) return C.TIME_UNSET

        val hours = matcher.group(1)?.toLongOrNull() ?: 0L
        val minutes = matcher.group(2)?.toLongOrNull() ?: return C.TIME_UNSET
        val seconds = matcher.group(3)?.toLongOrNull() ?: return C.TIME_UNSET
        val centiseconds = matcher.group(4)?.toLongOrNull() ?: return C.TIME_UNSET

        return try {
            var timestampUs = Math.multiplyExact(hours, 60L * 60L * C.MICROS_PER_SECOND)
            timestampUs = Math.addExact(
                timestampUs,
                Math.multiplyExact(minutes, 60L * C.MICROS_PER_SECOND)
            )
            timestampUs = Math.addExact(
                timestampUs,
                Math.multiplyExact(seconds, C.MICROS_PER_SECOND)
            )
            Math.addExact(timestampUs, Math.multiplyExact(centiseconds, 10_000L))
        } catch (_: ArithmeticException) {
            C.TIME_UNSET
        }
    }

    private fun findTokenIndex(array: ByteArray, limit: Int, tokenNumber: Int): Int {
        if (tokenNumber <= 0) return 0
        var tokensFound = 0
        for (index in 0 until limit) {
            if (array[index] == COMMA && ++tokensFound == tokenNumber) {
                return index + 1
            }
        }
        return -1
    }

    private companion object {
        val SSA_TIMECODE_PATTERN: Pattern =
            Pattern.compile("""(?:(\d+):)?(\d+):(\d+)[:.](\d+)""")

        const val COMMA = ','.code.toByte()
    }
}
