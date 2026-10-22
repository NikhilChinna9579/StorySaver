package com.storysaver.app.downloads

import android.content.Context
import android.os.SystemClock
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.storysaver.app.appContainer
import kotlinx.coroutines.CancellationException

/**
 * Runs one download in the background as a foreground job (with a progress notification), so it
 * keeps going when the user leaves the app or locks the phone.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val request: DownloadRequest? by lazy {
        DownloadRequest.fromJson(inputData.getString(KEY_REQUEST).orEmpty())
    }
    private val title: String get() = request?.title ?: "Download"

    override suspend fun getForegroundInfo(): ForegroundInfo =
        DownloadNotifications.foreground(applicationContext, id, title, "Starting…", null)

    override suspend fun doWork(): Result {
        val req = request ?: return Result.failure(workDataOf(KEY_ERROR to "This download is no longer valid."))
        try {
            setForeground(getForegroundInfo())
        } catch (_: Exception) {
            // Android can refuse a foreground start in some background states; the job still runs.
        }

        var lastStep = ""
        var lastPercent = Int.MIN_VALUE
        var lastAt = 0L
        val progress = { step: String, fraction: Float? ->
            val percent = fraction?.let { (it * 100).toInt() } ?: -1
            val now = SystemClock.elapsedRealtime()
            if (step != lastStep || (percent != lastPercent && now - lastAt >= 400)) {
                lastStep = step; lastPercent = percent; lastAt = now
                setProgressAsync(workDataOf(KEY_STEP to step, KEY_PROGRESS to (fraction ?: -1f)))
                DownloadNotifications.progress(applicationContext, id, title, step, fraction)
            }
        }

        return try {
            val media = DownloadEngine(applicationContext.appContainer).run(req, progress)
            DownloadNotifications.done(applicationContext, id, media)
            Result.success(workDataOf(KEY_MEDIA_ID to media.id))
        } catch (e: CancellationException) {
            DownloadNotifications.cancel(applicationContext, id)
            throw e
        } catch (e: DownloadFailure) {
            val msg = e.message ?: "The download failed."
            DownloadNotifications.failed(applicationContext, id, title, msg)
            Result.failure(workDataOf(KEY_ERROR to msg))
        } catch (e: Exception) {
            val msg = "The download failed (${e.javaClass.simpleName}: ${e.message})."
            DownloadNotifications.failed(applicationContext, id, title, msg)
            Result.failure(workDataOf(KEY_ERROR to msg))
        }
    }

    companion object {
        const val KEY_REQUEST = "request"
        const val KEY_STEP = "step"
        const val KEY_PROGRESS = "progress"
        const val KEY_MEDIA_ID = "media_id"
        const val KEY_ERROR = "error"
    }
}
