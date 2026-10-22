package com.storysaver.app.ui.downloads

import com.storysaver.app.model.Platform
import com.storysaver.app.model.SavedMedia
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

enum class SourceFilter(val label: String) {
    ALL("All"), INSTAGRAM("Instagram"), YOUTUBE("YouTube"), WHATSAPP("WhatsApp"), OTHER("Shared");

    fun matches(p: Platform) = when (this) {
        ALL -> true
        INSTAGRAM -> p == Platform.INSTAGRAM
        YOUTUBE -> p == Platform.YOUTUBE
        WHATSAPP -> p == Platform.WHATSAPP
        OTHER -> p == Platform.SHARED
    }
}

enum class TypeFilter(val label: String) {
    ALL("Any type"), VIDEO("Videos"), IMAGE("Images"), AUDIO("Audio");

    fun matches(m: SavedMedia) = when (this) {
        ALL -> true
        VIDEO -> m.mimeType.startsWith("video/")
        IMAGE -> m.mimeType.startsWith("image/")
        AUDIO -> m.isAudio
    }
}

enum class SortOrder(val label: String) {
    NEWEST("Newest first"), OLDEST("Oldest first"), LARGEST("Largest first"), NAME("Name (A–Z)")
}

data class DownloadsQuery(
    val text: String = "",
    val source: SourceFilter = SourceFilter.ALL,
    val type: TypeFilter = TypeFilter.ALL,
    val sort: SortOrder = SortOrder.NEWEST,
)

data class DownloadGroup(val title: String, val items: List<SavedMedia>)

/** Search, filter, sort and date grouping for the Downloads tab. Pure — unit-tested. */
object DownloadsFilter {

    fun apply(items: List<SavedMedia>, q: DownloadsQuery): List<SavedMedia> {
        val words = q.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val filtered = items.filter { m ->
            q.source.matches(m.source) && q.type.matches(m) &&
                words.all { w -> m.displayName.lowercase().contains(w) || m.source.label.lowercase().contains(w) }
        }
        return when (q.sort) {
            SortOrder.NEWEST -> filtered.sortedByDescending { it.savedAt }
            SortOrder.OLDEST -> filtered.sortedBy { it.savedAt }
            SortOrder.LARGEST -> filtered.sortedByDescending { it.sizeBytes }
            SortOrder.NAME -> filtered.sortedBy { it.displayName.lowercase() }
        }
    }

    /** Date headers ("Today", "Yesterday", "This week", "22 Oct 2026") when sorted by date; one group otherwise. */
    fun group(items: List<SavedMedia>, sort: SortOrder, now: Long, tz: TimeZone = TimeZone.getDefault()): List<DownloadGroup> {
        if (items.isEmpty()) return emptyList()
        if (sort != SortOrder.NEWEST && sort != SortOrder.OLDEST) return listOf(DownloadGroup("", items))
        val groups = LinkedHashMap<String, MutableList<SavedMedia>>()
        items.forEach { groups.getOrPut(label(it.savedAt, now, tz)) { mutableListOf() }.add(it) }
        return groups.map { (k, v) -> DownloadGroup(k, v) }
    }

    fun label(time: Long, now: Long, tz: TimeZone): String {
        val days = dayIndex(now, tz) - dayIndex(time, tz)
        return when {
            days <= 0 -> "Today"
            days == 1L -> "Yesterday"
            days < 7 -> "This week"
            else -> SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).apply { timeZone = tz }.format(time)
        }
    }

    private fun dayIndex(t: Long, tz: TimeZone): Long {
        return (t + tz.getOffset(t)) / 86_400_000L
    }

    fun totalBytes(items: List<SavedMedia>): Long = items.sumOf { it.sizeBytes.coerceAtLeast(0) }
}
