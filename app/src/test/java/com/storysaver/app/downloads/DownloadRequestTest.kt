package com.storysaver.app.downloads

import com.storysaver.app.media.TrimRange
import com.storysaver.app.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadRequestTest {

    @Test fun directRoundTrip() {
        val r = DownloadRequest.Direct("https://x.cdninstagram.com/a.mp4?oh=1&oe=2", "@ndtv reel", Platform.INSTAGRAM, audioOnly = true)
        assertEquals(r, DownloadRequest.fromJson(r.toJson()))
    }

    @Test fun youtubeRoundTripWithAndWithoutAudio() {
        val merge = DownloadRequest.YouTube("Title \"quoted\" / ünïcode", "https://rr1.googlevideo.com/v?x=1",
            "https://rr1.googlevideo.com/a", 123_456_789L, 3_000_000L, 615, "1080p60")
        assertEquals(merge, DownloadRequest.fromJson(merge.toJson()))
        val single = merge.copy(audioUrl = null, audioLength = -1, qualityLabel = "360p")
        assertEquals(single, DownloadRequest.fromJson(single.toJson()))
        val audio = merge.copy(audioUrl = null, audioOnly = true, qualityLabel = "Audio only")
        assertEquals(audio, DownloadRequest.fromJson(audio.toJson()))
        assertEquals(Platform.YOUTUBE, audio.platform)
    }

    @Test fun trimSurvivesTheRoundTrip() {
        val d = DownloadRequest.Direct("https://x/a.mp4", "@ndtv · video · clip", Platform.INSTAGRAM, false,
            "ndtv_instagram_X", TrimRange(5_000, 20_000))
        assertEquals(d, DownloadRequest.fromJson(d.toJson()))
        val y = DownloadRequest.YouTube("T", "https://v", "https://a", 1, 2, 60, "720p", trim = TrimRange(0, 1_500))
        assertEquals(y, DownloadRequest.fromJson(y.toJson()))
    }

    @Test fun localRoundTrip() {
        val l = DownloadRequest.Local("content://media/external/video/media/42", "video/mp4", "clip", Platform.WHATSAPP,
            audioOnly = true, fileBase = "WhatsApp_status_2026-10-22_11-51-16_audio", trim = TrimRange(1_000, 4_000))
        assertEquals(l, DownloadRequest.fromJson(l.toJson()))
    }

    @Test fun jobsSavedByOlderVersionsHaveNoTrim() {
        val old = DownloadRequest.fromJson("""{"kind":"direct","url":"https://a/b.mp4","title":"t","platform":"INSTAGRAM","audioOnly":false}""")
        assertNull(old!!.trim)
        val broken = DownloadRequest.fromJson("""{"kind":"direct","url":"https://a/b.mp4","trimStart":9000,"trimEnd":3000}""")
        assertNull(broken!!.trim)
    }

    @Test fun badInputIsNull() {
        assertNull(DownloadRequest.fromJson("not json"))
        assertNull(DownloadRequest.fromJson("""{"kind":"teleport"}"""))
        assertNull(DownloadRequest.fromJson("""{"kind":"direct"}""")) // missing url
    }

    @Test fun unknownPlatformFallsBack() {
        val r = DownloadRequest.fromJson("""{"kind":"direct","url":"https://a/b.jpg","platform":"MYSPACE"}""")
        assertEquals(Platform.SHARED, (r as DownloadRequest.Direct).platform)
    }
}
