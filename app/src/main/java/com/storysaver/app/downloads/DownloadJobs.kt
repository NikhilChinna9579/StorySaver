package com.storysaver.app.downloads

import android.content.Context
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.storysaver.app.data.HistoryRepository
import com.storysaver.app.model.SavedMedia
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** State of one background download, as seen by the screens. */
sealed interface JobState {
    data object Queued : JobState
    data class Running(val step: String, val progress: Float?) : JobState
    data class Done(val media: SavedMedia?) : JobState
    data class Failed(val message: String) : JobState
    data object Cancelled : JobState

    val finished: Boolean get() = this is Done || this is Failed || this is Cancelled
}

data class ActiveJob(val id: UUID, val title: String, val state: JobState)

/** Starts, watches and cancels background downloads (WorkManager under the hood). */
class DownloadJobs(context: Context, private val history: HistoryRepository) {

    private val wm = WorkManager.getInstance(context)

    fun enqueue(request: DownloadRequest): UUID {
        val work = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_REQUEST to request.toJson()))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .addTag(TAG)
            .addTag(TITLE_PREFIX + request.title.take(120))
            .build()
        wm.enqueue(work)
        return work.id
    }

    fun observe(id: UUID): Flow<JobState> = wm.getWorkInfoByIdFlow(id).map { it.toState() }

    /** Downloads that haven't finished yet, for the Downloads tab. */
    fun active(): Flow<List<ActiveJob>> = wm.getWorkInfosByTagFlow(TAG).map { infos ->
        infos.filter { !it.state.isFinished }.map { info ->
            ActiveJob(
                id = info.id,
                title = info.tags.firstOrNull { it.startsWith(TITLE_PREFIX) }?.removePrefix(TITLE_PREFIX) ?: "Download",
                state = info.toState(),
            )
        }
    }

    fun cancel(id: UUID) {
        wm.cancelWorkById(id)
    }

    private fun WorkInfo?.toState(): JobState {
        val info = this ?: return JobState.Queued
        return info.stateOf()
    }

    private fun WorkInfo.stateOf(): JobState = when (state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> JobState.Queued
        WorkInfo.State.RUNNING -> JobState.Running(
            progress.getString(DownloadWorker.KEY_STEP) ?: "Starting",
            progress.getFloat(DownloadWorker.KEY_PROGRESS, -1f).takeIf { it >= 0f },
        )
        WorkInfo.State.SUCCEEDED -> {
            val mediaId = outputData.getString(DownloadWorker.KEY_MEDIA_ID)
            JobState.Done(history.items.value.firstOrNull { it.id == mediaId })
        }
        WorkInfo.State.FAILED -> JobState.Failed(outputData.getString(DownloadWorker.KEY_ERROR) ?: "The download failed.")
        WorkInfo.State.CANCELLED -> JobState.Cancelled
    }

    companion object {
        const val TAG = "storysaver-download"
        private const val TITLE_PREFIX = "title:"
    }
}
