package com.storysaver.app.ui.downloads

import com.storysaver.app.model.Platform
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.model.StorageKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class DownloadsFilterTest {

    private val tz = TimeZone.getTimeZone("Asia/Kolkata")
    private val day = 86_400_000L
    private val now = 1_792_675_501_000L // 22 Oct 2026, 18:55 IST

    private fun m(name: String, src: Platform, mime: String, ageMs: Long, size: Long = 1000) =
        SavedMedia(name, "content://x/$name", name, mime, size, now - ageMs, src, StorageKind.MEDIA_STORE, "Movies/StorySaver")

    private val items = listOf(
        m("ndtv_instagram_A.mp4", Platform.INSTAGRAM, "video/mp4", 60_000, 5_000),
        m("ndtv_instagram_A_audio.m4a", Platform.INSTAGRAM, "audio/mp4", 120_000, 300),
        m("Me at the zoo - jawed (360p).mp4", Platform.YOUTUBE, "video/mp4", day, 9_000),
        m("WhatsApp_status_2026-10-18.jpg", Platform.WHATSAPP, "image/jpeg", 4 * day, 200),
        m("Holiday photo.jpg", Platform.SHARED, "image/jpeg", 30 * day, 700),
    )

    private fun names(q: DownloadsQuery) = DownloadsFilter.apply(items, q).map { it.displayName }

    @Test fun defaultIsNewestFirst() {
        assertEquals(items.map { it.displayName }, names(DownloadsQuery()))
    }

    @Test fun filterBySourceAndType() {
        assertEquals(listOf("ndtv_instagram_A.mp4", "ndtv_instagram_A_audio.m4a"), names(DownloadsQuery(source = SourceFilter.INSTAGRAM)))
        assertEquals(listOf("ndtv_instagram_A_audio.m4a"), names(DownloadsQuery(type = TypeFilter.AUDIO)))
        assertEquals(listOf("ndtv_instagram_A.mp4", "Me at the zoo - jawed (360p).mp4"), names(DownloadsQuery(type = TypeFilter.VIDEO)))
        assertEquals(listOf("WhatsApp_status_2026-10-18.jpg"), names(DownloadsQuery(source = SourceFilter.WHATSAPP, type = TypeFilter.IMAGE)))
        assertEquals(emptyList<String>(), names(DownloadsQuery(source = SourceFilter.YOUTUBE, type = TypeFilter.IMAGE)))
        assertEquals(listOf("Holiday photo.jpg"), names(DownloadsQuery(source = SourceFilter.OTHER)))
    }

    @Test fun searchIsCaseInsensitiveAndMatchesAllWords() {
        assertEquals(listOf("Me at the zoo - jawed (360p).mp4"), names(DownloadsQuery(text = "ZOO jawed")))
        assertEquals(2, names(DownloadsQuery(text = "ndtv")).size)
        assertEquals(1, names(DownloadsQuery(text = "youtube")).size) // matches the source too
        assertEquals(0, names(DownloadsQuery(text = "zoo cat")).size)
    }

    @Test fun sortOrders() {
        assertEquals("Holiday photo.jpg", names(DownloadsQuery(sort = SortOrder.OLDEST)).first())
        assertEquals("Me at the zoo - jawed (360p).mp4", names(DownloadsQuery(sort = SortOrder.LARGEST)).first())
        assertEquals("Holiday photo.jpg", names(DownloadsQuery(sort = SortOrder.NAME)).first())
    }

    @Test fun dateGroups() {
        val groups = DownloadsFilter.group(DownloadsFilter.apply(items, DownloadsQuery()), SortOrder.NEWEST, now, tz)
        assertEquals(listOf("Today", "Yesterday", "This week", "22 Sep 2026"), groups.map { it.title })
        assertEquals(2, groups[0].items.size)
        assertEquals(listOf(""), DownloadsFilter.group(items, SortOrder.LARGEST, now, tz).map { it.title })
        assertEquals(emptyList<DownloadGroup>(), DownloadsFilter.group(emptyList(), SortOrder.NEWEST, now, tz))
    }

    @Test fun totalSize() {
        assertEquals(15_200, DownloadsFilter.totalBytes(items))
    }
}
