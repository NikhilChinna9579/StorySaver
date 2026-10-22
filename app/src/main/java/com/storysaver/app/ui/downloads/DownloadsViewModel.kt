package com.storysaver.app.ui.downloads

import android.content.IntentSender
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storysaver.app.AppContainer
import com.storysaver.app.data.MediaSaver
import com.storysaver.app.model.SavedMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ConsentRequest(val media: SavedMedia, val intentSender: IntentSender)

class DownloadsViewModel(private val c: AppContainer) : ViewModel() {

    val items: StateFlow<List<SavedMedia>> = c.history.items

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _consent = MutableStateFlow<ConsentRequest?>(null)
    /** Set when Android needs the user to confirm deleting a gallery item. */
    val consent: StateFlow<ConsentRequest?> = _consent.asStateFlow()

    fun refresh() {
        viewModelScope.launch { c.history.pruneMissing() }
    }

    fun delete(media: SavedMedia) {
        viewModelScope.launch {
            when (val r = c.saver.delete(media)) {
                MediaSaver.DeleteOutcome.Deleted -> {
                    c.history.remove(media.id)
                    _message.value = "Deleted ${media.displayName}"
                }
                is MediaSaver.DeleteOutcome.NeedsConsent -> _consent.value = ConsentRequest(media, r.intentSender)
                is MediaSaver.DeleteOutcome.Failed -> _message.value = r.message
            }
        }
    }

    fun onConsentResult(approved: Boolean) {
        val req = _consent.value ?: return
        _consent.value = null
        if (!approved) return
        // On Android 11+ the system already deleted it; on Android 10 we retry now that we're allowed.
        delete(req.media)
    }

    fun removeFromHistoryOnly(media: SavedMedia) = c.history.remove(media.id)

    fun messageShown() {
        _message.value = null
    }
}
