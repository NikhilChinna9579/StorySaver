package com.storysaver.app.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import com.storysaver.app.MainActivity
import com.storysaver.app.model.SavedMedia
import java.util.UUID

/** Progress / finished / failed notifications for background downloads. */
object DownloadNotifications {

    private const val CHANNEL = "downloads"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress and results of downloads"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun progressId(job: UUID) = job.hashCode()
    private fun resultId(job: UUID) = job.hashCode() + 1

    private fun progressNotification(context: Context, job: UUID, title: String, step: String, fraction: Float?) =
        NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.take(80))
            .setContentText(if (fraction != null) "$step · ${(fraction * 100).toInt()}%" else step)
            .setProgress(100, ((fraction ?: 0f) * 100).toInt(), fraction == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Cancel", WorkManager.getInstance(context).createCancelPendingIntent(job))
            .build()

    fun foreground(context: Context, job: UUID, title: String, step: String, fraction: Float?): ForegroundInfo {
        val n = progressNotification(context, job, title, step, fraction)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(progressId(job), n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(progressId(job), n)
        }
    }

    fun progress(context: Context, job: UUID, title: String, step: String, fraction: Float?) =
        post(context, progressId(job)) { progressNotification(context, job, title, step, fraction) }

    fun done(context: Context, job: UUID, media: SavedMedia) {
        cancel(context, job)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(media.uri), media.mimeType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val tap = PendingIntent.getActivity(
            context, resultId(job), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        post(context, resultId(job)) {
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Saved: ${media.displayName}")
                .setContentText("In ${media.locationLabel} · tap to open")
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
        }
    }

    fun failed(context: Context, job: UUID, title: String, message: String) {
        cancel(context, job)
        post(context, resultId(job)) {
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Download failed: ${title.take(60)}")
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
        }
    }

    fun cancel(context: Context, job: UUID) = NotificationManagerCompat.from(context).cancel(progressId(job))

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    /** Posting silently does nothing if the user turned notifications off — downloads still run. */
    private inline fun post(context: Context, id: Int, build: () -> android.app.Notification) {
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, build())
        } catch (_: SecurityException) {
        }
    }
}
