package com.storysaver.app.ui.download

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storysaver.app.AppContainer
import com.storysaver.app.data.MediaSaver
import com.storysaver.app.data.SharedItem
import com.storysaver.app.data.StatusItem
import com.storysaver.app.data.StatusRepository
import com.storysaver.app.data.UriInfo
import com.storysaver.app.model.MediaType
import com.storysaver.app.model.Platform
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.util.MimeUtils
import com.storysaver.app.util.UrlValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LocalUiState(
    val platform: Platform,
    val mediaType: MediaType,
    val folderGranted: Boolean = false,
    val folderLabel: String? = null,
    val loading: Boolean = false,
    val items: List<StatusItem> = emptyList(),
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    val skippedCount: Int = 0,
    val skippedNames: List<String> = emptyList(),
    val scannedFolders: List<String> = emptyList(),
    val listedOnce: Boolean = false,
    val selected: StatusItem? = null,
    val saving: Boolean = false,
    val progress: Float? = null,
    val saved: SavedMedia? = null,
    val error: String? = null,
    val notice: String? = null,
    val requestStoragePermission: Boolean = false,
    val destinationLabel: String = "",
)

/** WhatsApp status browsing + saving any locally readable media (also used for shared files). */
class LocalSaveViewModel(
    platform: Platform,
    initialType: MediaType,
    private val c: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(
        LocalUiState(platform = platform, mediaType = initialType, destinationLabel = c.saver.destinationLabel(initialType))
    )
    val state: StateFlow<LocalUiState> = _state.asStateFlow()
    private var saveJob: Job? = null
    private var lastScan: StatusRepository.Scan? = null
    private var refreshJob: Job? = null

    init {
        if (platform == Platform.WHATSAPP) {
            val folder = c.settings.statusFolder.value
            if (folder != null && c.statuses.hasAccess(folder)) {
                _state.update { it.copy(folderGranted = true, folderLabel = c.saver.folderLabel(folder)) }
                refresh()
            }
        }
        if (platform == Platform.SHARED) {
            (c.shareInbox.take() as? SharedItem.Media)?.let { onFilePicked(it.uri, it.mimeType) }
        }
    }

    fun onMediaTypeChange(type: MediaType) {
        if (_state.value.saving) return
        _state.update {
            it.copy(mediaType = type, selected = null, saved = null, error = null,
                destinationLabel = c.saver.destinationLabel(type))
        }
        val scan = lastScan
        if (scan != null) _state.update { it.copy(items = scan.of(type)) } else if (_state.value.folderGranted) refresh()
    }

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        try {
            c.statuses.persistAccess(uri)
            c.settings.setStatusFolder(uri)
            _state.update { it.copy(folderGranted = true, folderLabel = c.saver.folderLabel(uri), error = null) }
            refreshJob?.cancel()
            lastScan = null
            refresh()
        } catch (e: SecurityException) {
            _state.update { it.copy(error = "Android didn't allow access to that folder. Try selecting it again.") }
        }
    }

    fun forgetFolder() {
        c.settings.setStatusFolder(null)
        lastScan = null
        _state.update { it.copy(folderGranted = false, folderLabel = null, items = emptyList(), listedOnce = false, selected = null) }
    }

    /** Re-reads the status folder. [quiet] = no spinner (used when returning to the app). */
    fun refresh(quiet: Boolean = false) {
        val folder = c.settings.statusFolder.value ?: return
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = true, error = null) }
            try {
                val scan = c.statuses.scan(folder)
                lastScan = scan
                _state.update {
                    it.copy(
                        loading = false, listedOnce = true,
                        items = scan.of(it.mediaType),
                        imageCount = scan.of(MediaType.IMAGE).size,
                        videoCount = scan.of(MediaType.VIDEO).size,
                        skippedCount = scan.skipped.size,
                        skippedNames = scan.skipped,
                        scannedFolders = scan.folders,
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SecurityException) {
                c.settings.setStatusFolder(null)
                _state.update {
                    it.copy(loading = false, folderGranted = false, items = emptyList(),
                        error = "Access to the status folder was revoked. Please grant it again.")
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(loading = false, listedOnce = true,
                        error = "Couldn't read the folder (${e.message ?: "unknown error"}). Try choosing it again.")
                }
            }
        }
    }

    /** A single file chosen with the system document picker (or shared from another app). */
    fun onFilePicked(uri: Uri?, reportedMime: String? = null) {
        if (uri == null) return
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) { UriInfo.meta(c.appContext, uri) }
            val mime = MimeUtils.resolve(meta.mime ?: reportedMime, meta.name)
            val type = MimeUtils.typeFromMime(mime)
            if (mime == null || type == null) {
                _state.update { it.copy(error = "That file isn't an image or video Story Saver can save.") }
                return@launch
            }
            val item = StatusItem(uri, meta.name ?: "Selected file", mime, System.currentTimeMillis(), meta.size)
            _state.update {
                it.copy(
                    selected = item, saved = null, error = null,
                    mediaType = type,
                    destinationLabel = c.saver.destinationLabel(type),
                    notice = if (type != it.mediaType) "Switched to ${type.label} to match the file you picked." else null,
                )
            }
        }
    }

    fun select(item: StatusItem) = _state.update { it.copy(selected = item, saved = null, error = null) }

    fun clearSelection() = _state.update { it.copy(selected = null, saved = null) }

    fun onPastedText(text: String?) {
        if (text.isNullOrBlank()) {
            _state.update { it.copy(error = "Your clipboard is empty.") }
            return
        }
        if (UrlValidator.looksLikeUrl(text)) {
            _state.update {
                it.copy(
                    notice = "WhatsApp status updates don't have public, downloadable links, so a pasted link " +
                        "can't retrieve someone's status. View the status in WhatsApp first, then pick it from " +
                        "the status folder below.",
                )
            }
        } else {
            _state.update { it.copy(notice = "That isn't a link. Pick a status from the folder or a file instead.") }
        }
    }

    fun save() {
        val item = _state.value.selected ?: return
        if (c.saver.needsLegacyPermission()) {
            _state.update { it.copy(requestStoragePermission = true) }
            return
        }
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            _state.update { it.copy(saving = true, progress = null, error = null) }
            val outcome = c.saver.save(item.uri, item.mimeType, _state.value.platform) { p ->
                _state.update { it.copy(progress = p) }
            }
            when (outcome) {
                is MediaSaver.Outcome.Saved -> {
                    c.history.add(outcome.media)
                    _state.update { it.copy(saving = false, saved = outcome.media) }
                }
                is MediaSaver.Outcome.Failed -> _state.update { it.copy(saving = false, error = outcome.message) }
                MediaSaver.Outcome.NeedsStoragePermission ->
                    _state.update { it.copy(saving = false, requestStoragePermission = true) }
            }
        }
    }

    fun cancelSave() {
        saveJob?.cancel()
        _state.update { it.copy(saving = false, error = "Cancelled.") }
    }

    fun onStoragePermissionResult(granted: Boolean) {
        _state.update { it.copy(requestStoragePermission = false) }
        if (granted) save() else _state.update {
            it.copy(error = "Storage permission is needed on Android 9 and older to save into your gallery. " +
                "Alternatively, choose a custom folder in Settings.")
        }
    }

    fun dismissMessages() = _state.update { it.copy(error = null, notice = null) }
}
