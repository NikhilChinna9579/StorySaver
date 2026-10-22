package com.storysaver.app.ui.download

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storysaver.app.AppContainer
import com.storysaver.app.data.DirectMediaDownloader
import com.storysaver.app.data.MediaSaver
import com.storysaver.app.data.SharedItem
import com.storysaver.app.model.MediaType
import com.storysaver.app.model.Platform
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.util.UrlCheck
import com.storysaver.app.util.UrlValidator
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

sealed interface IgPhase {
    data object Idle : IgPhase
    data class Fetching(val progress: Float?) : IgPhase
    data class Ready(val file: File, val mimeType: String, val sizeBytes: Long) : IgPhase
    data class Saving(val ready: Ready, val progress: Float?) : IgPhase
    data class Saved(val media: SavedMedia) : IgPhase
}

/** An honest "we can't do this" — deliberately different from a download failure. */
data class Unsupported(val title: String, val message: String)

data class IgUiState(
    val url: String = "",
    val mediaType: MediaType,
    val phase: IgPhase = IgPhase.Idle,
    val error: String? = null,
    val unsupported: Unsupported? = null,
    val requestStoragePermission: Boolean = false,
    val destinationLabel: String = "",
)

class InstagramViewModel(initialType: MediaType, private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(
        IgUiState(mediaType = initialType, destinationLabel = c.saver.destinationLabel(initialType))
    )
    val state: StateFlow<IgUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        // A link shared into the app from another app's share sheet.
        (c.shareInbox.pending.value as? SharedItem.Text)?.let {
            c.shareInbox.take()
            onUrlChange(UrlValidator.extractFirstUrl(it.text) ?: it.text)
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value, error = null, unsupported = null) }

    fun onPaste(clipboard: String?) {
        if (clipboard.isNullOrBlank()) {
            _state.update { it.copy(error = "Your clipboard is empty. Copy a link first.") }
            return
        }
        onUrlChange(UrlValidator.extractFirstUrl(clipboard) ?: clipboard.trim())
    }

    fun onMediaTypeChange(type: MediaType) {
        if (_state.value.phase is IgPhase.Fetching || _state.value.phase is IgPhase.Saving) return
        discardTemp()
        _state.update {
            it.copy(mediaType = type, phase = IgPhase.Idle, error = null, destinationLabel = c.saver.destinationLabel(type))
        }
    }

    fun fetch() {
        val s = _state.value
        _state.update { it.copy(error = null, unsupported = null) }
        when (val check = UrlValidator.check(s.url)) {
            is UrlCheck.Invalid -> _state.update { it.copy(error = check.reason) }
            UrlCheck.NotInstagram -> _state.update {
                it.copy(error = "This isn't an Instagram link. Paste a link from instagram.com or an Instagram media link.")
            }
            is UrlCheck.StoryPage -> _state.update { it.copy(unsupported = storyUnsupported()) }
            is UrlCheck.PostPage -> _state.update { it.copy(unsupported = postUnsupported(check.kind)) }
            UrlCheck.OtherInstagramPage -> _state.update {
                it.copy(
                    unsupported = Unsupported(
                        "This link isn't a media file",
                        "It opens an Instagram page (such as a profile), not an image or video. " +
                            "Paste a direct media link instead.",
                    )
                )
            }
            is UrlCheck.DirectMedia -> {
                if (check.guessedType != null && check.guessedType != s.mediaType) {
                    _state.update {
                        it.copy(error = "This link looks like a ${check.guessedType.label.lowercase()}, but " +
                            "${s.mediaType.label} is selected. Switch the type above.")
                    }
                    return
                }
                startDownload(check.url, s.mediaType)
            }
        }
    }

    private fun startDownload(url: String, type: MediaType) {
        discardTemp()
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(phase = IgPhase.Fetching(null)) }
            val result = c.downloader.fetch(url, type) { p ->
                _state.update { st -> if (st.phase is IgPhase.Fetching) st.copy(phase = IgPhase.Fetching(p)) else st }
            }
            _state.update {
                when (result) {
                    is DirectMediaDownloader.Result.Success ->
                        it.copy(phase = IgPhase.Ready(result.file, result.mimeType, result.sizeBytes))
                    is DirectMediaDownloader.Result.Failure -> it.copy(phase = IgPhase.Idle, error = result.message)
                }
            }
        }
    }

    fun save() {
        val ready = when (val p = _state.value.phase) {
            is IgPhase.Ready -> p
            else -> return
        }
        if (c.saver.needsLegacyPermission()) {
            _state.update { it.copy(requestStoragePermission = true) }
            return
        }
        job = viewModelScope.launch {
            _state.update { it.copy(phase = IgPhase.Saving(ready, null), error = null) }
            val outcome = c.saver.save(Uri.fromFile(ready.file), ready.mimeType, Platform.INSTAGRAM) { p ->
                _state.update { st -> if (st.phase is IgPhase.Saving) st.copy(phase = IgPhase.Saving(ready, p)) else st }
            }
            when (outcome) {
                is MediaSaver.Outcome.Saved -> {
                    c.history.add(outcome.media)
                    ready.file.delete()
                    _state.update { it.copy(phase = IgPhase.Saved(outcome.media)) }
                }
                is MediaSaver.Outcome.Failed -> _state.update { it.copy(phase = ready, error = outcome.message) }
                MediaSaver.Outcome.NeedsStoragePermission ->
                    _state.update { it.copy(phase = ready, requestStoragePermission = true) }
            }
        }
    }

    fun onStoragePermissionResult(granted: Boolean) {
        _state.update { it.copy(requestStoragePermission = false) }
        if (granted) save() else _state.update {
            it.copy(error = "Storage permission is needed on Android 9 and older to save into your gallery. " +
                "Alternatively, choose a custom folder in Settings.")
        }
    }

    fun cancel() {
        job?.cancel()
        val p = _state.value.phase
        _state.update { it.copy(phase = if (p is IgPhase.Saving) p.ready else IgPhase.Idle, error = "Cancelled.") }
    }

    fun reset() {
        job?.cancel()
        discardTemp()
        _state.update { it.copy(url = "", phase = IgPhase.Idle, error = null, unsupported = null) }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun discardTemp() {
        when (val p = _state.value.phase) {
            is IgPhase.Ready -> p.file.delete()
            is IgPhase.Saving -> p.ready.file.delete()
            else -> Unit
        }
    }

    override fun onCleared() {
        discardTemp()
    }

    private fun storyUnsupported() = Unsupported(
        "Story links can't be downloaded directly",
        "An Instagram Story link opens the story page in Instagram — it doesn't point to the image or video " +
            "file itself, and Instagram offers no public way to fetch story media from it. Story Saver won't " +
            "log in for you, scrape pages, or get around private accounts.\n\n" +
            "What you can do instead:\n" +
            "• For your own stories, use Instagram's Archive or the story's Save option.\n" +
            "• If you have a direct media link (on cdninstagram.com or fbcdn.net), paste that here.\n" +
            "• If another app shares the actual image or video file, use Share → Story Saver.",
    )

    private fun postUnsupported(kind: String) = Unsupported(
        "This $kind link isn't a media file",
        "Instagram $kind links open a web page, not the underlying image or video, so they can't be " +
            "downloaded with a supported method. Paste a direct media link instead, or use Share → Story Saver " +
            "from an app that shares the file itself.",
    )
}
