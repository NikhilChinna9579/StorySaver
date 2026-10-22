package com.storysaver.app.ui.download

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storysaver.app.AppContainer
import com.storysaver.app.data.MediaSaver
import com.storysaver.app.data.SharedItem
import com.storysaver.app.downloads.DownloadRequest
import com.storysaver.app.downloads.JobState
import java.util.UUID
import com.storysaver.app.media.TrimRange
import com.storysaver.app.model.Platform
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.util.FileNaming
import com.storysaver.app.util.Progress
import com.storysaver.app.util.formatBytes
import com.storysaver.app.youtube.ChunkedDownloader
import com.storysaver.app.youtube.Mp4Merger
import com.storysaver.app.youtube.YouTubeExtractor
import com.storysaver.app.youtube.YouTubeLinks
import com.storysaver.app.youtube.YtLink
import com.storysaver.app.youtube.YtQuality
import com.storysaver.app.youtube.YtVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface YtJob {
    data object Ready : YtJob
    /** step = "Downloading video", "Downloading audio", "Merging", "Saving" */
    data class Working(val step: String, val progress: Float?) : YtJob
    data class Saved(val media: SavedMedia) : YtJob
    data class Failed(val message: String) : YtJob
}

data class YtUiState(
    val url: String = "",
    val resolving: Boolean = false,
    val video: YtVideo? = null,
    val selected: YtQuality? = null,
    val job: YtJob = YtJob.Ready,
    val error: String? = null,
    val requestStoragePermission: Boolean = false,
) {
    val busy: Boolean get() = resolving || job is YtJob.Working
}

class YouTubeViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(YtUiState())
    val state: StateFlow<YtUiState> = _state.asStateFlow()
    private var job: Job? = null
    private var jobId: UUID? = null

    init {
        (c.shareInbox.pending.value as? SharedItem.Text)?.let {
            if (YouTubeLinks.parse(it.text) is YtLink.Video) {
                c.shareInbox.take()
                onUrlChange(it.text.trim())
                find()
            }
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value, error = null) }

    fun onPaste(clipboard: String?) {
        if (clipboard.isNullOrBlank()) {
            _state.update { it.copy(error = "Your clipboard is empty. In YouTube tap Share → Copy link first.") }
            return
        }
        onUrlChange(clipboard.trim())
        find()
    }

    fun find() {
        if (_state.value.busy) return
        val link = when (val l = YouTubeLinks.parse(_state.value.url)) {
            is YtLink.Video -> l
            is YtLink.NotAVideo -> {
                _state.update {
                    it.copy(error = when (l.what) {
                        "playlist" -> "That's a playlist link. Open one video from it, tap Share → Copy link, and paste that."
                        "channel" -> "That's a channel link. Open a video, tap Share → Copy link, and paste that."
                        else -> "That YouTube link doesn't point to a video. Open the video, tap Share → Copy link, and paste that."
                    })
                }
                return
            }
            null -> {
                _state.update {
                    it.copy(error = if (_state.value.url.isBlank()) "Paste a YouTube link first."
                    else "This isn't a YouTube link. In YouTube, open the video, tap Share → Copy link, and paste it here.")
                }
                return
            }
        }
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(resolving = true, video = null, selected = null, job = YtJob.Ready, error = null) }
            val result = withContext(Dispatchers.IO) { c.youtube.resolve(link) }
            _state.update {
                when (result) {
                    is YouTubeExtractor.Result.Success -> it.copy(
                        resolving = false,
                        video = result.video,
                        selected = com.storysaver.app.youtube.YouTubeFormats.defaultChoice(result.video.qualities),
                    )
                    is YouTubeExtractor.Result.Failure -> it.copy(resolving = false, error = result.message)
                }
            }
        }
    }

    fun select(q: YtQuality) {
        if (_state.value.job is YtJob.Working) return
        _state.update { it.copy(selected = q, job = YtJob.Ready) }
    }

    private var pendingTrim: TrimRange? = null

    fun download(trim: TrimRange? = null) {
        val s = _state.value
        val video = s.video ?: return
        val q = s.selected ?: return
        if (s.job is YtJob.Working) return
        if (c.saver.needsLegacyPermission()) {
            pendingTrim = trim
            _state.update { it.copy(requestStoragePermission = true) }
            return
        }
        val request = DownloadRequest.YouTube(
            title = (if (q.audioOnly) "${video.title} · audio" else "${video.title} · ${q.label}") +
                if (trim != null) " · clip" else "",
            videoUrl = q.video.url,
            audioUrl = q.audio?.url,
            videoLength = q.video.contentLength,
            audioLength = q.audio?.contentLength ?: -1,
            durationSeconds = video.durationSeconds,
            qualityLabel = q.label,
            audioOnly = q.audioOnly,
            fileBase = FileNaming.youtubeBase(video.title, video.channel, q.label, q.audioOnly),
            trim = trim,
        )
        val id = c.jobs.enqueue(request)
        jobId = id
        _state.update { it.copy(job = YtJob.Working("Starting", null)) }
        job = viewModelScope.launch {
            c.jobs.observe(id).onEach { st ->
                _state.update {
                    it.copy(job = when (st) {
                        JobState.Queued -> YtJob.Working("Waiting", null)
                        is JobState.Running -> YtJob.Working(st.step, st.progress)
                        is JobState.Done -> st.media?.let { m -> YtJob.Saved(m) }
                            ?: YtJob.Failed("Saved, but the file couldn't be found afterwards.")
                        is JobState.Failed -> YtJob.Failed(st.message)
                        JobState.Cancelled -> YtJob.Ready
                    })
                }
            }.first { it.finished }
        }
    }

    fun cancel() {
        jobId?.let { c.jobs.cancel(it) }
        job?.cancel()
        _state.update {
            it.copy(resolving = false, job = if (it.job is YtJob.Working) YtJob.Ready else it.job)
        }
    }

    fun onStoragePermissionResult(granted: Boolean) {
        _state.update { it.copy(requestStoragePermission = false) }
        if (granted) download(pendingTrim) else _state.update {
            it.copy(error = "Storage permission is needed on Android 9 and older to save into your gallery. " +
                "Alternatively, choose a custom folder in Settings.")
        }
    }

    fun reset() {
        job?.cancel()
        _state.update { YtUiState() }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
