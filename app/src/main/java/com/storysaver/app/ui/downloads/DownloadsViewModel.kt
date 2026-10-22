package com.storysaver.app.ui.downloads

import android.content.IntentSender
import android.net.Uri
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storysaver.app.AppContainer
import com.storysaver.app.data.MediaSaver
import com.storysaver.app.data.UriInfo
import com.storysaver.app.downloads.ActiveJob
import com.storysaver.app.model.SavedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Android must confirm deleting gallery files the app no longer "owns" (e.g. after a reinstall). */
data class ConsentRequest(val media: List<SavedMedia>, val intentSender: IntentSender)

data class DownloadsUi(
    val query: DownloadsQuery = DownloadsQuery(),
    val groups: List<DownloadGroup> = emptyList(),
    val shownCount: Int = 0,
    val shownBytes: Long = 0,
    val totalCount: Int = 0,
    val selected: Set<String> = emptySet(),
) {
    val selecting: Boolean get() = selected.isNotEmpty()
}

class DownloadsViewModel(private val c: AppContainer) : ViewModel() {

    val items: StateFlow<List<SavedMedia>> = c.history.items

    /** Background downloads still running. */
    val active: StateFlow<List<ActiveJob>> =
        c.jobs.active().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val query = MutableStateFlow(DownloadsQuery())
    private val selection = MutableStateFlow<Set<String>>(emptySet())

    val ui: StateFlow<DownloadsUi> = combine(items, query, selection) { all, q, sel ->
        val shown = DownloadsFilter.apply(all, q)
        val ids = all.map { it.id }.toSet()
        DownloadsUi(
            query = q,
            groups = DownloadsFilter.group(shown, q.sort, System.currentTimeMillis()),
            shownCount = shown.size,
            shownBytes = DownloadsFilter.totalBytes(shown),
            totalCount = all.size,
            selected = sel intersect ids,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUi())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _consent = MutableStateFlow<ConsentRequest?>(null)
    val consent: StateFlow<ConsentRequest?> = _consent.asStateFlow()

    fun cancelJob(id: UUID) = c.jobs.cancel(id)

    // ---- search / filters ----
    fun setText(t: String) = query.update { it.copy(text = t) }
    fun setSource(s: SourceFilter) = query.update { it.copy(source = s) }
    fun setType(t: TypeFilter) = query.update { it.copy(type = t) }
    fun setSort(s: SortOrder) = query.update { it.copy(sort = s) }
    fun clearFilters() = query.update { DownloadsQuery(sort = it.sort) }

    // ---- selection ----
    fun toggleSelect(id: String) = selection.update { if (id in it) it - id else it + id }
    fun selectAllShown() = selection.update { ui.value.groups.flatMap { g -> g.items.map { it.id } }.toSet() }
    fun clearSelection() = selection.update { emptySet() }
    fun selectedMedia(): List<SavedMedia> = items.value.filter { it.id in ui.value.selected }

    fun refresh() {
        viewModelScope.launch { c.history.pruneMissing() }
    }

    fun find(id: String): SavedMedia? = items.value.firstOrNull { it.id == id }

    fun delete(media: SavedMedia) = deleteMany(listOf(media))

    fun deleteSelected() {
        deleteMany(selectedMedia())
        clearSelection()
    }

    private fun deleteMany(list: List<SavedMedia>) {
        if (list.isEmpty()) return
        viewModelScope.launch {
            var deleted = 0
            val needConsent = mutableListOf<Pair<SavedMedia, IntentSender>>()
            val failures = mutableListOf<String>()
            for (m in list) {
                when (val r = c.saver.delete(m)) {
                    MediaSaver.DeleteOutcome.Deleted -> { c.history.remove(m.id); deleted++ }
                    is MediaSaver.DeleteOutcome.NeedsConsent -> needConsent += m to r.intentSender
                    is MediaSaver.DeleteOutcome.Failed -> failures += r.message
                }
            }
            if (needConsent.isNotEmpty()) {
                val media = needConsent.map { it.first }
                // Android 11+: one system dialog for all of them. Android 10: one at a time.
                val sender = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    c.saver.batchDeleteRequest(media.map { Uri.parse(it.uri) }) ?: needConsent.first().second
                } else {
                    needConsent.first().second
                }
                _consent.value = ConsentRequest(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) media else listOf(media.first()), sender,
                )
            }
            _message.value = when {
                failures.isNotEmpty() -> failures.first()
                deleted == 1 && list.size == 1 -> "Deleted ${list.first().displayName}"
                deleted > 0 -> "Deleted $deleted files"
                else -> null
            }
        }
    }

    fun onConsentResult(approved: Boolean) {
        val req = _consent.value ?: return
        _consent.value = null
        if (!approved) return
        viewModelScope.launch {
            // On Android 11+ the system already deleted them; on 10 we retry now that we're allowed.
            var removed = 0
            for (m in req.media) {
                val gone = withContext(Dispatchers.IO) { !UriInfo.exists(c.appContext, Uri.parse(m.uri)) }
                if (gone || c.saver.delete(m) == MediaSaver.DeleteOutcome.Deleted) {
                    c.history.remove(m.id); removed++
                }
            }
            if (removed > 0) _message.value = if (removed == 1) "Deleted ${req.media.first().displayName}" else "Deleted $removed files"
        }
    }

    fun removeFromHistoryOnly(media: SavedMedia) = c.history.remove(media.id)

    fun messageShown() {
        _message.value = null
    }
}
