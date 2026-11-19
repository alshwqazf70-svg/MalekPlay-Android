package com.malekplay.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.malekplay.R

/** Owns notification channel creation, rendering and rate-limited progress refreshes. */
internal class UltraShareNotificationHelper(
    private val context: Context,
    private val channelId: String,
    private val notificationId: Int,
    private val cancelAction: String
) {
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    @Volatile private var lastProgressNotificationAt = 0L

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                context.getString(R.string.ultra_share_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.ultra_share_notification_description)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    fun cancel() = manager.cancel(notificationId)

    @Synchronized
    fun notifyProgress(state: UltraShareServiceState, progress: UltraShareCore.Progress?) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressNotificationAt < 650L) return
        lastProgressNotificationAt = now
        manager.notify(notificationId, build(state, progress))
    }

    fun build(state: UltraShareServiceState, progress: UltraShareCore.Progress?): Notification {
        val text = when {
            state.sending && state.receiving -> "الإرسال والاستقبال يعملان معاً"
            progress != null -> "${progress.fileName} • ${(progress.fraction * 100).toInt()}%"
            else -> context.getString(R.string.ultra_share_notification_active)
        }
        val cancelIntent = PendingIntent.getService(
            context,
            38742,
            Intent(context, UltraShareForegroundService::class.java).setAction(cancelAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_ultra_share_notification)
            .addAction(0, context.getString(R.string.ultra_share_cancel_transfer), cancelIntent)
            .setContentTitle(context.getString(R.string.ultra_share_title))
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress?.let { (it.fraction * 100).toInt() } ?: 0, progress == null)
            .build()
    }
}
