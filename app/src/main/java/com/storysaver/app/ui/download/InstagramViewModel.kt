package com.storysaver.app.ui.download

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storysaver.app.AppContainer
import com.storysaver.app.data.DirectMediaDownloader
import com.storysaver.app.data.MediaSaver
import com.storysaver.app.data.SharedItem
import com.storysaver.app.instagram.IgMediaItem
import com.storysaver.app.instagram.IgPost
import com.storysaver.app.instagram.InstagramExtractor
import com.storysaver.app.model.Platform
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.util.UrlCheck
import com.storysaver.app.util.UrlValidator
import com.storysaver.app.youtube.YouTubeLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface IgItemState {
    data object Ready : IgItemState
    data class Downloading(val progress: Float?) : IgItemState
    data class Saving(val progress: Float?) : IgItemState
    data class Saved(val media: SavedMedia) : IgItemState
    data class Failed(val message: String) : IgItemState
}

data class IgItemUi(val item: IgMediaItem, val state: IgItemState = IgItemState.Ready)

sealed interface IgPhase {
    data object Idle : IgPhase
    data object Resolving : IgPhase
    data class Found(val post: IgPost, val items: List<IgItemUi>) : IgPhase
}

/** An honest "we can't do this" — shown differently from a failed download. */
data class Unsupported(val title: String, val message: String)

data class IgUiState(
    val url: String = "",
    val phase: IgPhase = IgPhase.Idle,
    val error: String? = null,
    val unsupported: Unsupported? = null,
    val requestStoragePermission: Boolean = false,
) {
    val busy: Boolean
        get() = phase is IgPhase.Resolving || (phase as? IgPhase.Found)?.items?.any {
            it.state is IgItemState.Downloading || it.state is IgItemState.Saving
        } == true
}

class InstagramViewModel(private val c: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(IgUiState())
    val state: StateFlow<IgUiState> = _state.asStateFlow()

    private var resolveJob: Job? = null
    private var saveJob: Job? = null
    /** Items waiting for the Android 8–9 storage permission. */
    private var pendingSave: List<Int> = emptyList()

    init {
        // A link shared into the app from Instagram's share sheet: fill it in and look it up right away.
        (c.shareInbox.pending.value as? SharedItem.Text)?.let {
            c.shareInbox.take()
            onUrlChange(UrlValidator.extractFirstUrl(it.text) ?: it.text)
            find()
        }
    }

    fun onUrlChange(value: String) = _state.update { it.copy(url = value, error = null, unsupported = null) }

    fun onPaste(clipboard: String?) {
        if (clipboard.isNullOrBlank()) {
            _state.update { it.copy(error = "Your clipboard is empty. In Instagram tap Share → Copy link first.") }
            return
        }
        onUrlChange(UrlValidator.extractFirstUrl(clipboard) ?: clipboard.trim())
        find()
    }

    /** Looks the link up and lists the media it contains. */
    fun find() {
        if (_state.value.busy) return
        val check = UrlValidator.check(_state.value.url)
        _state.update { it.copy(error = null, unsupported = null) }
        when (check) {
            is UrlCheck.Invalid -> {
                _state.update { it.copy(error = check.reason, phase = IgPhase.Idle) }
                return
            }
            UrlCheck.NotInstagram -> {
                if (YouTubeLinks.parse(_state.value.url) != null) {
                    _state.update {
                        it.copy(phase = IgPhase.Idle,
                            error = "That's a YouTube link. Go back, choose Video, then YouTube, and paste it there.")
                    }
                    return
                }
                _state.update {
                    it.copy(phase = IgPhase.Idle,
                        error = "This isn't an Instagram link. In Instagram, open the reel or post, tap Share → Copy link, and paste it here.")
                }
                return
            }
            UrlCheck.OtherInstagramPage -> {
                _state.update {
                    it.copy(phase = IgPhase.Idle, unsupported = Unsupported(
                        "That's not a post or reel link",
                        "This link opens an Instagram page such as a profile. Open the specific reel or post, " +
                            "tap Share → Copy link, and paste that instead.",
                    ))
                }
                return
            }
            is UrlCheck.StoryPage -> {
                _state.update {
                    it.copy(phase = IgPhase.Idle,
                        unsupported = Unsupported("Stories can't be downloaded from a link", InstagramExtractor.STORY_MESSAGE))
                }
                return
            }
            else -> Unit
        }

        resolveJob?.cancel()
        resolveJob = viewModelScope.launch {
            _state.update { it.copy(phase = IgPhase.Resolving) }
            val result = withContext(Dispatchers.IO) { c.instagram.resolve(check) }
            _state.update {
                when (result) {
                    is InstagramExtractor.Result.Success -> it.copy(
                        phase = IgPhase.Found(result.post, result.post.items.map { item -> IgItemUi(item) }),
                    )
                    is InstagramExtractor.Result.Failure ->
                        if (result.reason == InstagramExtractor.Reason.UNSUPPORTED) {
                            it.copy(phase = IgPhase.Idle, unsupported = Unsupported("Can't download this link", result.message))
                        } else {
                            it.copy(phase = IgPhase.Idle, error = result.message)
                        }
                }
            }
        }
    }

    fun save(index: Int) = startSaving(listOf(index))

    fun saveAll() {
        val found = _state.value.phase as? IgPhase.Found ?: return
        startSaving(found.items.filter { it.state !is IgItemState.Saved }.map { it.item.index })
    }

    private fun startSaving(indices: List<Int>) {
        if (indices.isEmpty() || saveJob?.isActive == true) return
        if (c.saver.needsLegacyPermission()) {
            pendingSave = indices
            _state.update { it.copy(requestStoragePermission = true) }
            return
        }
        saveJob = viewModelScope.launch {
            for (index in indices) saveOne(index)
        }
    }

    private suspend fun saveOne(index: Int) {
        val found = _state.value.phase as? IgPhase.Found ?: return
        val item = found.items.firstOrNull { it.item.index == index }?.item ?: return
        setItem(index, IgItemState.Downloading(null))

        val fetched = c.downloader.fetch(item.url, null) { p -> setItem(index, IgItemState.Downloading(p)) }
        val file = when (fetched) {
            is DirectMediaDownloader.Result.Success -> fetched
            is DirectMediaDownloader.Result.Failure -> {
                setItem(index, IgItemState.Failed(fetched.message))
                return
            }
        }
        setItem(index, IgItemState.Saving(null))
        try {
            when (val outcome = c.saver.save(Uri.fromFile(file.file), file.mimeType, Platform.INSTAGRAM) { p ->
                setItem(index, IgItemState.Saving(p))
            }) {
                is MediaSaver.Outcome.Saved -> {
                    c.history.add(outcome.media)
                    setItem(index, IgItemState.Saved(outcome.media))
                }
                is MediaSaver.Outcome.Failed -> setItem(index, IgItemState.Failed(outcome.message))
                MediaSaver.Outcome.NeedsStoragePermission -> {
                    setItem(index, IgItemState.Ready)
                    pendingSave = listOf(index)
                    _state.update { it.copy(requestStoragePermission = true) }
                }
            }
        } finally {
            file.file.delete()
        }
    }

    private fun setItem(index: Int, newState: IgItemState) = _state.update { s ->
        val found = s.phase as? IgPhase.Found ?: return@update s
        s.copy(phase = found.copy(items = found.items.map { if (it.item.index == index) it.copy(state = newState) else it }))
    }

    fun onStoragePermissionResult(granted: Boolean) {
        _state.update { it.copy(requestStoragePermission = false) }
        val pending = pendingSave
        pendingSave = emptyList()
        if (granted) startSaving(pending) else _state.update {
            it.copy(error = "Storage permission is needed on Android 9 and older to save into your gallery. " +
                "Alternatively, choose a custom folder in Settings.")
        }
    }

    fun cancel() {
        resolveJob?.cancel()
        saveJob?.cancel()
        _state.update { s ->
            when (val p = s.phase) {
                IgPhase.Resolving -> s.copy(phase = IgPhase.Idle)
                is IgPhase.Found -> s.copy(phase = p.copy(items = p.items.map {
                    if (it.state is IgItemState.Downloading || it.state is IgItemState.Saving) it.copy(state = IgItemState.Ready) else it
                }))
                else -> s
            }
        }
    }

    fun reset() {
        cancel()
        _state.update { IgUiState() }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
