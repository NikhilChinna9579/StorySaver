package com.storysaver.app.util

import com.storysaver.app.downloads.DownloadRequest
import com.storysaver.app.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ReadableNamesTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val t = 1_792_675_501_000L // 2026-10-22 13:25:01 UTC

    @Test fun instagramNames() {
        assertEquals("ndtv_instagram_DeyQf_PzHlD", FileNaming.instagramBase("ndtv", "DeyQf_PzHlD", 0, 1, false))
        assertEquals("ndtv_instagram_C9xYz_2", FileNaming.instagramBase("ndtv", "C9xYz", 1, 3, false))
        assertEquals("ndtv_instagram_DeyQf_PzHlD_audio", FileNaming.instagramBase("ndtv", "DeyQf_PzHlD", 0, 1, true))
        assertEquals("instagram", FileNaming.instagramBase(null, null, 0, 1, false))
        assertEquals("instagram_DeyQf_PzHlD", FileNaming.instagramBase(null, "DeyQf_PzHlD", 0, 1, false))
        assertEquals("ndtv_instagram_DeyQf_PzHlD.mp4",
            FileNaming.readable(FileNaming.instagramBase("ndtv", "DeyQf_PzHlD", 0, 1, false), Platform.INSTAGRAM, "video/mp4", t))
    }

    @Test fun youtubeNames() {
        assertEquals("Me at the zoo - jawed (360p)", FileNaming.youtubeBase("Me at the zoo", "jawed", "360p", false))
        assertEquals("Me at the zoo - jawed", FileNaming.youtubeBase("Me at the zoo", "jawed", "Audio only", true))
        // Channel already in the title → not repeated
        assertEquals("NDTV Live: news (720p60)", FileNaming.youtubeBase("NDTV Live: news", "NDTV", "720p60", false))
        // ':' is illegal on some file systems → replaced; audio gets .m4a
        assertEquals("NDTV Live news (720p60).mp4",
            FileNaming.readable(FileNaming.youtubeBase("NDTV Live: news", "NDTV", "720p60", false), Platform.YOUTUBE, "video/mp4", t))
        assertEquals("Song - Artist.m4a", FileNaming.readable("Song - Artist", Platform.YOUTUBE, "audio/mp4", t))
    }

    @Test fun whatsappAndSharedNames() {
        assertEquals("WhatsApp_status_2026-10-22_13-25-01", FileNaming.whatsappBase(t, utc))
        assertEquals("Holiday photo", FileNaming.sharedBase("Holiday photo.jpg", t, utc))
        // Meaningless hash-like names get a dated name instead
        assertEquals("Shared_2026-10-22_13-25-01", FileNaming.sharedBase("3F9A77B2C1D0E4F5.jpg", t, utc))
        assertEquals("Shared_2026-10-22_13-25-01", FileNaming.sharedBase(null, t, utc))
    }

    @Test fun cleaningKeepsLanguagesAndDropsTroublemakers() {
        assertEquals("தமிழ் பாடல் हिंदी गाना", FileNaming.cleanTitle("தமிழ் பாடல் | हिंदी गाना 🎵✨"))
        assertEquals("a b c d", FileNaming.cleanTitle("a/b\\c*d?\"<>"))
        assertEquals("Title", FileNaming.cleanTitle("  ..Title..  "))
        assertEquals("tab new line", FileNaming.cleanTitle("tab\tnew\nline"))
        assertEquals("", FileNaming.cleanTitle("🎉🎉"))
    }

    @Test fun lengthIsCappedInCharactersAndBytes() {
        assertTrue(FileNaming.cleanTitle("x".repeat(500)).length <= 100)
        val tamil = FileNaming.cleanTitle("த".repeat(200)) // 3 bytes per char in UTF-8
        assertTrue(tamil.toByteArray(Charsets.UTF_8).size <= 180)
    }

    @Test fun blankBaseFallsBackToGeneratedName() {
        val name = FileNaming.readable("🎉", Platform.INSTAGRAM, "video/mp4", t)
        assertTrue(name, name.startsWith("StorySaver_IG_") && name.endsWith(".mp4"))
        assertTrue(FileNaming.readable(null, Platform.WHATSAPP, "image/jpeg", t).startsWith("StorySaver_WA_"))
    }

    @Test fun requestsCarryTheName() {
        val r = DownloadRequest.Direct("https://x/a.mp4", "t", Platform.INSTAGRAM, false, "ndtv_instagram_X")
        assertEquals("ndtv_instagram_X", DownloadRequest.fromJson(r.toJson())!!.fileBase)
        val old = """{"kind":"direct","url":"https://x/a.mp4","title":"t","platform":"INSTAGRAM"}""" // queued by v1.2
        assertEquals(null, DownloadRequest.fromJson(old)!!.fileBase)
    }

    @Test fun clipNames() {
        val r = com.storysaver.app.media.TrimRange(5_000, 20_000)
        assertEquals("ndtv_instagram_DeyQf_PzHlD_clip_0m05s-0m20s", FileNaming.withClip("ndtv_instagram_DeyQf_PzHlD", r))
        assertEquals("Me at the zoo - jawed (360p) clip 0m05s-0m20s", FileNaming.withClip("Me at the zoo - jawed (360p)", r))
        assertEquals("ndtv_instagram_DeyQf_PzHlD", FileNaming.withClip("ndtv_instagram_DeyQf_PzHlD", null))
        assertEquals(null, FileNaming.withClip(null, r))
        assertEquals("ndtv_instagram_DeyQf_PzHlD_audio_clip_0m05s-0m20s.m4a",
            FileNaming.readable(FileNaming.withClip("ndtv_instagram_DeyQf_PzHlD_audio", r), Platform.INSTAGRAM, "audio/mp4", t))
    }
}
