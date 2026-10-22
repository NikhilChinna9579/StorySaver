package com.storysaver.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import com.storysaver.app.model.Platform
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.model.StorageKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Download history kept locally in SharedPreferences (newest first). */
class HistoryRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("history", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<SavedMedia>> = _items.asStateFlow()

    @Synchronized
    fun add(media: SavedMedia) {
        _items.value = listOf(media) + _items.value.filterNot { it.id == media.id }
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        persist()
    }

    /** Drops entries whose file was deleted outside the app. */
    suspend fun pruneMissing() = withContext(Dispatchers.IO) {
        val current = _items.value
        val alive = current.filter { UriInfo.exists(context, Uri.parse(it.uri)) }
        if (alive.size != current.size) synchronized(this@HistoryRepository) {
            val aliveIds = alive.map { it.id }.toSet()
            _items.value = _items.value.filter { it.id in aliveIds }
            persist()
        }
    }

    private fun persist() {
        val arr = JSONArray()
        _items.value.forEach { m ->
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("uri", m.uri)
                    .put("name", m.displayName)
                    .put("mime", m.mimeType)
                    .put("size", m.sizeBytes)
                    .put("savedAt", m.savedAt)
                    .put("source", m.source.name)
                    .put("storage", m.storage.name)
                    .put("location", m.locationLabel)
            )
        }
        prefs.edit { putString(KEY, arr.toString()) }
    }

    private fun load(): List<SavedMedia> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                SavedMedia(
                    id = o.getString("id"),
                    uri = o.getString("uri"),
                    displayName = o.optString("name", "media"),
                    mimeType = o.optString("mime", "image/jpeg"),
                    sizeBytes = o.optLong("size", -1),
                    savedAt = o.optLong("savedAt", 0),
                    source = runCatching { Platform.valueOf(o.getString("source")) }
                        .getOrDefault(Platform.SHARED),
                    storage = runCatching { StorageKind.valueOf(o.getString("storage")) }
                        .getOrDefault(StorageKind.MEDIA_STORE),
                    locationLabel = o.optString("location", ""),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val KEY = "items"
    }
}
