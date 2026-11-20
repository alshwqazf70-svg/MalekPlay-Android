package com.malekplay.network

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** OpenSubtitles 64-bit movie hash (file size + first/last 64 KiB, unsigned overflow). */
object OpenSubtitlesMovieHash {
    private const val CHUNK_SIZE = 64 * 1024
    private const val UINT_MASK = 0xFFFF_FFFFL

    fun calculate(file: File): String {
        require(file.isFile && file.length() >= CHUNK_SIZE * 2L) { "ملف الفيديو صغير جدًا لحساب بصمة OpenSubtitles" }
        FileInputStream(file).use { input ->
            return calculate(input.channel, file.length())
        }
    }

    fun calculate(context: Context, uri: Uri): String {
        require(uri.scheme == "file" || uri.scheme == "content") { "مصدر الفيديو غير مدعوم لحساب البصمة" }
        if (uri.scheme == "file") return calculate(File(requireNotNull(uri.path)))
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("تعذر فتح ملف الفيديو لحساب البصمة")
        descriptor.use { pfd ->
            val size = pfd.statSize
            require(size >= CHUNK_SIZE * 2L) { "ملف الفيديو صغير جدًا لحساب بصمة OpenSubtitles" }
            FileInputStream(pfd.fileDescriptor).use { input -> return calculate(input.channel, size) }
        }
    }

    private fun calculate(channel: FileChannel, size: Long): String {
        var hash = size
        val buffer = ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        fun addChunk(position: Long) {
            buffer.clear()
            channel.position(position)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) error("تعذر قراءة بيانات الفيديو لحساب البصمة")
            }
            buffer.flip()
            while (buffer.remaining() >= 8) hash += buffer.long
            while (buffer.hasRemaining()) hash += (buffer.get().toLong() and 0xffL)
        }
        addChunk(0L)
        addChunk(size - CHUNK_SIZE)
        return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }
}
