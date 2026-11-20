package com.malekplay.network

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** OpenSubtitles 64-bit movie hash (file size + first/last 64 KiB, unsigned overflow). */
object OpenSubtitlesMovieHash {
    private const val CHUNK_SIZE = 64 * 1024

    fun calculate(file: File): String {
        require(file.isFile && file.length() >= CHUNK_SIZE * 2L) {
            "ملف الفيديو صغير جدًا لحساب بصمة OpenSubtitles"
        }
        FileInputStream(file).use { input ->
            return calculate(input.channel, file.length())
        }
    }

    fun calculate(context: Context, uri: Uri): String {
        require(uri.scheme == "file" || uri.scheme == "content") {
            "مصدر الفيديو غير مدعوم لحساب البصمة"
        }
        if (uri.scheme == "file") return calculate(File(requireNotNull(uri.path)))
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: error("تعذر فتح ملف الفيديو لحساب البصمة")
        val size = descriptor.statSize
        require(size >= CHUNK_SIZE * 2L) {
            "ملف الفيديو صغير جدًا لحساب بصمة OpenSubtitles"
        }
        // Some content providers expose pipe-backed descriptors which cannot seek.
        // AutoCloseInputStream also owns and closes the ParcelFileDescriptor exactly once.
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            return calculate(input, size)
        }
    }

    private fun calculate(channel: FileChannel, size: Long): String {
        val buffer = ByteBuffer.allocate(CHUNK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        fun readChunk(position: Long): ByteArray {
            buffer.clear()
            channel.position(position)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) error("تعذر قراءة بيانات الفيديو لحساب البصمة")
            }
            return buffer.array().copyOf()
        }
        var hash = size
        hash = addChunk(hash, readChunk(0L))
        hash = addChunk(hash, readChunk(size - CHUNK_SIZE))
        return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }

    internal fun calculate(input: InputStream, size: Long): String {
        require(size >= CHUNK_SIZE * 2L) {
            "ملف الفيديو صغير جدًا لحساب بصمة OpenSubtitles"
        }
        val first = ByteArray(CHUNK_SIZE)
        val last = ByteArray(CHUNK_SIZE)
        readFully(input, first)
        skipFully(input, size - CHUNK_SIZE * 2L)
        readFully(input, last)
        var hash = size
        hash = addChunk(hash, first)
        hash = addChunk(hash, last)
        return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
    }

    private fun addChunk(initial: Long, bytes: ByteArray): Long {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var hash = initial
        while (buffer.remaining() >= 8) hash += buffer.long
        while (buffer.hasRemaining()) hash += (buffer.get().toLong() and 0xffL)
        return hash
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val count = input.read(target, offset, target.size - offset)
            if (count < 0) error("تعذر قراءة بيانات الفيديو لحساب البصمة")
            if (count > 0) offset += count
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val discard = ByteArray(8192)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = input.read(discard, 0, minOf(discard.size.toLong(), remaining).toInt())
                if (read < 0) error("تعذر الوصول إلى نهاية ملف الفيديو لحساب البصمة")
                remaining -= read
            }
        }
    }
}
