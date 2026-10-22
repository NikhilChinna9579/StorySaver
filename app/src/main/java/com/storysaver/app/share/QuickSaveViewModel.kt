package com.storysaver.app.share

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storysaver.app.AppContainer
import com.storysaver.app.data.MediaSaver
import com.storysaver.app.data.UriInfo
import com.storysaver.app.downloads.DownloadRequest
import com.storysaver.app.downloads.JobState
import com.storysaver.app.instagram.IgPost
import com.storysaver.app.instagram.InstagramExtractor
import com.storysaver.app.model.MediaType
import com.storysaver.app.model.Platform
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.util.FileNaming
import com.storysaver.app.util.MimeUtils
import com.storysaver.app.youtube.Mp4Merger
import com.storysaver.app.youtube.YouTubeExtractor
import com.storysaver.app.youtube.YouTubeFormats
import com.storysaver.app.youtube.YtQuality
import com.storysaver.app.youtube.YtVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** What another app shared with us. */
sealed interface ShareInput {
    data class Text(val text: String) : ShareInput
    data class File(val uri: Uri, val mime: String?) : ShareInput
}

/** What the sheet shows. */
sealed interface QsContent {
    data class Loading(val message: String) : QsContent
    data class Unsupported(val message: String) : QsContent

    /** Instagram post/reel. [selected] = item indexes; [audioOnly] applies to videos. */
    data class Instagram(val post: IgPost, val selected: Set<Int>, val audioOnly: Boolean = false) : QsContent {
        val isVideo: Boolean get() = post.items.any { it.type == MediaType.VIDEO }
        val isAllImages: Boolean get() = post.items.all { it.type == MediaType.IMAGE }
    }

    data class YouTube(val video: YtVideo, val selected: YtQuality?) : QsContent

    /** A file shared directly (e.g. from the gallery or WhatsApp). */
    data class LocalFile(val uri: Uri, val mime: String, val name: String, val size: Long, val audioOnly: Boolean = false) : QsContent {
        val isVideo: Boolean get() = mime.startsWith("video/")
        /** Audio extraction works for MP4 video with AAC sound. */
        val canExtractAudio: Boolean get() = mime == "video/mp4"
    }
}

sealed interface QsProgress {
    data object Idle : QsProgress
    /** Handed to the background; [step]/[fraction] mirror the first job. */
    data class Background(val count: Int, val step: String, val fraction: Float?) : QsProgress
    data class Saving(val step: String) : QsProgress
    data class Saved(val media: List<SavedMedia>) : QsProgress
    data class Failed(val message: String) : QsProgress
}

data class QsState(
    val content: QsContent,
    val progress: QsProgress = QsProgress.Idle,
    val requestStoragePermission: Boolean = false,
)

/** Drives the "Share → Story Saver" sheet: look up what was shared, offer options, download. */
class QuickSaveViewModel(input: ShareInput, private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(QsState(QsContent.Loading("Looking it up…")))
    val state: StateFlow<QsState> = _state.asStateFlow()
    private val jobIds = mutableListOf<UUID>()

    init {
        when (input) {
            is ShareInput.Text -> lookUp(input.text)
            is ShareInput.File -> loadFile(input)
        }
    }

    private fun lookUp(text: String) {
        when (val target = ShareRouter.route(text)) {
            is ShareTarget.Unsupported -> set(QsContent.Unsupported(target.message))
            is ShareTarget.Instagram -> viewModelScope.launch {
                set(QsContent.Loading("Looking up the Instagram post…"))
                when (val r = withContext(Dispatchers.IO) { c.instagram.resolve(target.check) }) {
                    is InstagramExtractor.Result.Success ->
                        set(QsContent.Instagram(r.post, r.post.items.map { it.index }.toSet()))
                    is InstagramExtractor.Result.Failure -> set(QsContent.Unsupported(r.message))
                }
            }
            is ShareTarget.YouTube -> viewModelScope.launch {
                set(QsContent.Loading("Looking up the YouTube video…"))
                when (val r = withContext(Dispatchers.IO) { c.youtube.resolve(target.link) }) {
                    is YouTubeExtractor.Result.Success ->
                        set(QsContent.YouTube(r.video, YouTubeFormats.defaultChoice(r.video.qualities)))
                    is YouTubeExtractor.Result.Failure -> set(QsContent.Unsupported(r.message))
                }
            }
        }
    }

    private fun loadFile(input: ShareInput.File) = viewModelScope.launch {
        val meta = withContext(Dispatchers.IO) { UriInfo.meta(c.appContext, input.uri) }
        val mime = MimeUtils.resolve(meta.mime ?: input.mime, meta.name) ?: input.mime?.takeIf { MimeUtils.isSavable(it) }
        if (mime == null || MimeUtils.typeFromMime(mime) == null) {
            set(QsContent.Unsupported("That file isn't an image or video Story Saver can save."))
            return@launch
        }
        set(QsContent.LocalFile(input.uri, mime, meta.name ?: "Shared file", meta.size))
    }

    private fun set(content: QsContent) = _state.update { it.copy(content = content) }

    // ---- choices ----

    fun toggleItem(index: Int) = _state.update { s ->
        val c = s.content as? QsContent.Instagram ?: return@update s
        val sel = if (index in c.selected) c.selected - index else c.selected + index
        s.copy(content = c.copy(selected = sel))
    }

    fun setAudioOnly(audioOnly: Boolean) = _state.update { s ->
        when (val c = s.content) {
            is QsContent.Instagram -> s.copy(content = c.copy(audioOnly = audioOnly))
            is QsContent.LocalFile -> s.copy(content = c.copy(audioOnly = audioOnly))
            else -> s
        }
    }

    fun selectQuality(q: YtQuality) = _state.update { s ->
        val c = s.content as? QsContent.YouTube ?: return@update s
        s.copy(content = c.copy(selected = q))
    }

    // ---- download ----

    fun download() {
        val s = _state.value
        if (s.progress is QsProgress.Background || s.progress is QsProgress.Saving) return
        if (c.saver.needsLegacyPermission()) {
            _state.update { it.copy(requestStoragePermission = true) }
            return
        }
        when (val content = s.content) {
            is QsContent.Instagram -> {
                val owner = content.post.owner?.let { "@$it" } ?: "Instagram"
                val items = content.post.items.filter { it.index in content.selected }
                if (items.isEmpty()) return
                val requests = items.map { item ->
                    val audio = content.audioOnly && item.type == MediaType.VIDEO
                    val part = if (content.post.items.size > 1) " (${item.index + 1}/${content.post.items.size})" else ""
                    DownloadRequest.Direct(item.url, owner + part + if (audio) " · audio" else " · ${item.type.label.lowercase()}",
                        Platform.INSTAGRAM, audio,
                        FileNaming.instagramBase(content.post.owner, content.post.shortcode, item.index, content.post.items.size, audio))
                }
                startBackground(requests)
            }
            is QsContent.YouTube -> {
                val q = content.selected ?: return
                startBackground(listOf(DownloadRequest.YouTube(
                    title = if (q.audioOnly) "${content.video.title} · audio" else "${content.video.title} · ${q.label}",
                    videoUrl = q.video.url, audioUrl = q.audio?.url,
                    videoLength = q.video.contentLength, audioLength = q.audio?.contentLength ?: -1,
                    durationSeconds = content.video.durationSeconds, qualityLabel = q.label, audioOnly = q.audioOnly,
                    fileBase = FileNaming.youtubeBase(content.video.title, content.video.channel, q.label, q.audioOnly),
                )))
            }
            is QsContent.LocalFile -> saveLocal(content)
            else -> Unit
        }
    }

    private fun startBackground(requests: List<DownloadRequest>) {
        val ids = requests.map { c.jobs.enqueue(it) }
        jobIds += ids
        _state.update { it.copy(progress = QsProgress.Background(ids.size, "Starting", null)) }
        val done = mutableMapOf<UUID, JobState>()
        ids.forEach { id ->
            viewModelScope.launch {
                c.jobs.observe(id).onEach { st ->
                    if (st.finished) done[id] = st
                    _state.update { s -> s.copy(progress = combined(ids, done, id, st)) }
                }.first { it.finished }
            }
        }
    }

    private fun combined(ids: List<UUID>, done: Map<UUID, JobState>, id: UUID, latest: JobState): QsProgress {
        if (done.size == ids.size) {
            val failed = done.values.filterIsInstance<JobState.Failed>()
            val saved = done.values.filterIsInstance<JobState.Done>().mapNotNull { it.media }
            return when {
                saved.isNotEmpty() -> QsProgress.Saved(saved)
                failed.isNotEmpty() -> QsProgress.Failed(failed.first().message)
                else -> QsProgress.Idle // all cancelled
            }
        }
        val step = (latest as? JobState.Running)?.step ?: "Waiting"
        val fraction = (latest as? JobState.Running)?.progress
        return QsProgress.Background(ids.size, if (ids.size > 1) "${done.size}/${ids.size} done · $step" else step, fraction)
    }

    /** Files shared directly are copied right away (their access permission ends when the sheet closes). */
    private fun saveLocal(f: QsContent.LocalFile) = viewModelScope.launch {
        _state.update { it.copy(progress = QsProgress.Saving("Saving")) }
        var temp: File? = null
        try {
            val outcome = if (f.audioOnly && f.canExtractAudio) {
                _state.update { it.copy(progress = QsProgress.Saving("Extracting audio")) }
                val dir = File(c.appContext.cacheDir, "jobs").apply { mkdirs() }
                val src = File(dir, "share_${System.nanoTime()}.mp4").also { temp = it }
                withContext(Dispatchers.IO) {
                    c.appContext.contentResolver.openInputStream(f.uri)?.use { i -> src.outputStream().use { o -> i.copyTo(o) } }
                        ?: throw IOException("The shared file can't be read")
                }
                val audio = File(dir, "share_${System.nanoTime()}.m4a")
                try {
                    withContext(Dispatchers.IO) { Mp4Merger.extractAudio(src, audio, 0) {} }
                    c.saver.save(Uri.fromFile(audio), "audio/mp4", Platform.SHARED,
                        FileNaming.sharedBase(f.name, System.currentTimeMillis()) + "_audio")
                } finally {
                    audio.delete()
                }
            } else {
                c.saver.save(f.uri, f.mime, Platform.SHARED, FileNaming.sharedBase(f.name, System.currentTimeMillis()))
            }
            when (outcome) {
                is MediaSaver.Outcome.Saved -> {
                    c.history.add(outcome.media)
                    _state.update { it.copy(progress = QsProgress.Saved(listOf(outcome.media))) }
                }
                is MediaSaver.Outcome.Failed -> _state.update { it.copy(progress = QsProgress.Failed(outcome.message)) }
                MediaSaver.Outcome.NeedsStoragePermission ->
                    _state.update { it.copy(progress = QsProgress.Idle, requestStoragePermission = true) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            _state.update { it.copy(progress = QsProgress.Failed(e.message ?: "Couldn't save the file.")) }
        } finally {
            temp?.delete()
        }
    }

    fun cancel() {
        jobIds.forEach { c.jobs.cancel(it) }
    }

    fun onStoragePermissionResult(granted: Boolean) {
        _state.update { it.copy(requestStoragePermission = false) }
        if (granted) download() else _state.update {
            it.copy(progress = QsProgress.Failed("Storage permission is needed on Android 9 and older to save into your gallery."))
        }
    }
}
