package com.storysaver.app.ui.download

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storysaver.app.AppContainer
import com.storysaver.app.data.MediaSaver
import com.storysaver.app.data.SharedItem
import com.storysaver.app.model.Platform
import com.storysaver.app.model.SavedMedia
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

    fun download() {
        val s = _state.value
        val video = s.video ?: return
        val q = s.selected ?: return
        if (s.job is YtJob.Working) return
        if (c.saver.needsLegacyPermission()) {
            _state.update { it.copy(requestStoragePermission = true) }
            return
        }
        job = viewModelScope.launch {
            val dir = c.youtubeCacheDir.apply { mkdirs() }
            val stamp = System.nanoTime()
            val videoPart = File(dir, "v_$stamp.mp4")
            val audioPart = File(dir, "a_$stamp.m4a")
            val merged = File(dir, "out_$stamp.mp4")
            try {
                val needed = if (q.approxBytes > 0) q.approxBytes * 2 else 0
                if (needed > 0 && dir.usableSpace < needed + 50L * 1024 * 1024) {
                    fail("Not enough free storage. This needs about ${formatBytes(needed)} free while downloading.")
                    return@launch
                }

                val result: File
                if (q.needsMerge) {
                    val audio = q.audio!!
                    val vLen = q.video.contentLength
                    val aLen = audio.contentLength
                    val totalLen = if (vLen > 0 && aLen > 0) vLen + aLen else -1L
                    step("Downloading video", 0f)
                    c.chunkedDownloader.download(q.video.url, YouTubeExtractor.userAgentFor(q.video.url), videoPart, vLen) { done, total ->
                        val p = if (totalLen > 0) Progress.fraction(done, totalLen) else Progress.fraction(done, total)
                        step("Downloading video", p)
                    }
                    step("Downloading audio", if (totalLen > 0) Progress.fraction(vLen, totalLen) else null)
                    c.chunkedDownloader.download(audio.url, YouTubeExtractor.userAgentFor(audio.url), audioPart, aLen) { done, total ->
                        val p = if (totalLen > 0) Progress.fraction(vLen + done, totalLen) else Progress.fraction(done, total)
                        step("Downloading audio", p)
                    }
                    step("Merging video and audio", 0f)
                    withContext(Dispatchers.IO) {
                        Mp4Merger.merge(videoPart, audioPart, merged, video.durationSeconds * 1_000_000) { p ->
                            step("Merging video and audio", p)
                        }
                    }
                    videoPart.delete(); audioPart.delete()
                    result = merged
                } else {
                    step("Downloading video", 0f)
                    c.chunkedDownloader.download(q.video.url, YouTubeExtractor.userAgentFor(q.video.url), merged, q.video.contentLength) { done, total ->
                        step("Downloading video", Progress.fraction(done, total))
                    }
                    result = merged
                }

                step("Saving", null)
                when (val outcome = c.saver.save(Uri.fromFile(result), "video/mp4", Platform.YOUTUBE) { p -> step("Saving", p) }) {
                    is MediaSaver.Outcome.Saved -> {
                        c.history.add(outcome.media)
                        _state.update { it.copy(job = YtJob.Saved(outcome.media)) }
                    }
                    is MediaSaver.Outcome.Failed -> fail(outcome.message)
                    MediaSaver.Outcome.NeedsStoragePermission -> {
                        _state.update { it.copy(job = YtJob.Ready, requestStoragePermission = true) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChunkedDownloader.HttpError) {
                fail(
                    if (e.code == 403) "YouTube refused the download (HTTP 403). The link may have expired — tap Find video again and retry."
                    else "YouTube's server returned an error (HTTP ${e.code}). Please try again."
                )
            } catch (e: IOException) {
                val msg = e.message.orEmpty()
                fail(
                    if (msg.contains("ENOSPC") || msg.contains("No space", true)) "Your phone ran out of storage during the download."
                    else "The download failed: ${msg.ifBlank { "network error" }}. Check your connection and try again."
                )
            } finally {
                videoPart.delete(); audioPart.delete(); merged.delete()
            }
        }
    }

    private fun step(name: String, progress: Float?) =
        _state.update { if (it.job is YtJob.Saved) it else it.copy(job = YtJob.Working(name, progress)) }

    private fun fail(message: String) = _state.update { it.copy(job = YtJob.Failed(message)) }

    fun cancel() {
        job?.cancel()
        _state.update {
            it.copy(resolving = false, job = if (it.job is YtJob.Working) YtJob.Ready else it.job)
        }
    }

    fun onStoragePermissionResult(granted: Boolean) {
        _state.update { it.copy(requestStoragePermission = false) }
        if (granted) download() else _state.update {
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
