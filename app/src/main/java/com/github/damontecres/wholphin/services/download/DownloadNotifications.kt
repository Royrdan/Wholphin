package com.github.damontecres.wholphin.services.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import com.github.damontecres.wholphin.R

/** Foreground-service notification for the download queue. */
class DownloadNotifications(
    private val context: Context,
    private val cancelIntent: PendingIntent,
) {
    private val manager = NotificationManagerCompat.from(context)

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Downloads",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Progress of offline downloads"
                    setShowBadge(false)
                }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun contentIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun build(
        title: String,
        text: String,
        progress: Int?, // 0..100, null = indeterminate
        remaining: Int,
    ): Notification =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(if (remaining > 1) "$remaining in queue" else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (progress != null) setProgress(100, progress, false) else setProgress(0, 0, true)
            }.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Pause all", cancelIntent)
            .build()

    fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }

    fun update(notification: Notification) {
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    fun showFinished(
        done: Int,
        failed: Int,
    ) {
        if (done == 0 && failed == 0) return
        val text =
            buildString {
                if (done > 0) append("$done finished")
                if (failed > 0) {
                    if (isNotEmpty()) append(", ")
                    append("$failed failed")
                }
            }
        val n =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.downloads_title))
                .setContentText(text)
                .setAutoCancel(true)
                .setSilent(true)
                .setContentIntent(contentIntent())
                .build()
        runCatching { manager.notify(NOTIFICATION_ID + 1, n) }
    }

    companion object {
        const val CHANNEL_ID = "wholphin_downloads"
        const val NOTIFICATION_ID = 4201
    }
}
