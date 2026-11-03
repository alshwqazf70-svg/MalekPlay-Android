package com.malekplay.core.ui.util

import kotlin.math.abs

/** Formats milliseconds as H:MM:SS or M:SS. Returns a dash for unknown durations. */
fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "--:--"
    val totalSeconds = (millis / 1000L).coerceAtMost(359_999L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/** Compact form used in dense lists, e.g. "1:04:12" -> "1h04". */
fun formatDurationCompact(millis: Long): String {
    if (millis <= 0L) return "--"
    val totalMinutes = millis / 60_000L
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "%dh%02d".format(hours, minutes) else "%dm".format(minutes)
}

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "—"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "%d %s".format(bytes, units[0])
    else "%.1f %s".format(value, units[unit])
}

fun formatBitrate(bitsPerSecond: Long): String {
    if (bitsPerSecond <= 0L) return "—"
    return when {
        bitsPerSecond >= 1_000_000 -> "%.1f Mbps".format(bitsPerSecond / 1_000_000.0)
        bitsPerSecond >= 1_000 -> "%d kbps".format(bitsPerSecond / 1000)
        else -> "%d bps".format(bitsPerSecond)
    }
}

fun formatSpeed(speed: Float): String =
    if (abs(speed - speed.toInt().toFloat()) < 0.01f) "%d×".format(speed.toInt())
    else "%.2f×".format(speed)

/** Strips a filename extension for display. */
fun titleWithoutExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
}

/** "1920×1080" when known, otherwise an empty string. */
fun formatResolution(width: Int, height: Int): String =
    if (width > 0 && height > 0) "${width}×${height}" else ""

/** Human friendly "2 days ago" style stamp for the history list. */
fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (timestampMs <= 0L) return ""
    val diff = nowMs - timestampMs
    if (diff < 60_000L) return "now"
    val minutes = diff / 60_000L
    if (minutes < 60) return "%dm".format(minutes)
    val hours = minutes / 60
    if (hours < 24) return "%dh".format(hours)
    val days = hours / 24
    if (days < 30) return "%dd".format(days)
    return "%dmo".format(days / 30)
}
