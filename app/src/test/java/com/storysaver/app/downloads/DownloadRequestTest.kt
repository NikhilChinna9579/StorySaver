package com.storysaver.app.downloads

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
