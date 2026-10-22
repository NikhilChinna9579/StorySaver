package com.storysaver.app.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeFormatsTest {

    private fun v(h: Int, codec: String, mime: String = "video/mp4", fps: Int = 30, br: Int = h * 1000, len: Long = h * 10_000L,
                  audio: Boolean = false, progressive: Boolean = true) =
        StreamDesc("https://rr.googlevideo.com/v$h$codec$fps$audio", mime, codec, h, fps, br, len, true, audio, progressive)

    private fun a(codec: String, mime: String, br: Int, original: Boolean = true, len: Long = 3_000_000) =
        StreamDesc("https://rr.googlevideo.com/a$codec$br$original", mime, codec, 0, 0, br, len, false, true, true, original)

    /** Roughly what YouTube serves today for a 1080p60 video. */
    private val typical = listOf(
        v(360, "avc1.42001E", audio = true),                    // only combined stream
        v(144, "avc1.4d400c"), v(240, "avc1.4d4015"), v(360, "avc1.4d401e"), v(480, "avc1.4d401f"),
        v(720, "avc1.4d401f"), v(720, "avc1.640020", fps = 60), v(1080, "avc1.640028", fps = 60),
        v(1080, "vp9", "video/webm", fps = 60), v(1440, "vp9", "video/webm", fps = 60), v(2160, "av01.0.12M.08"),
        a("mp4a.40.2", "audio/mp4", 129_000), a("opus", "audio/webm", 160_000), a("mp4a.40.5", "audio/mp4", 48_000),
        a("mp4a.40.2", "audio/mp4", 200_000, original = false),  // dubbed track: must not be chosen
    )

    @Test fun offersH264ResolutionsUpTo1080() {
        val q = YouTubeFormats.qualities(typical)
        assertEquals(listOf("1080p60", "720p60", "480p", "360p", "240p", "144p"), q.map { it.label })
    }

    @Test fun prefersCombinedStreamAtSameHeight() {
        val q360 = YouTubeFormats.qualities(typical).single { it.height == 360 }
        assertTrue(q360.video.hasAudio)
        assertNull(q360.audio)
        assertTrue(!q360.needsMerge)
    }

    @Test fun pairsBestOriginalAacAudio() {
        val q1080 = YouTubeFormats.qualities(typical).first()
        assertTrue(q1080.needsMerge)
        assertEquals("mp4a.40.2", q1080.audio!!.codec)
        assertEquals(129_000, q1080.audio!!.bitrate) // not the 200k dubbed one, not opus
        assertEquals(1080 * 10_000L + 3_000_000, q1080.approxBytes)
    }

    @Test fun ignoresManifestsAndWebmOnly() {
        val q = YouTubeFormats.qualities(listOf(
            v(720, "avc1", progressive = false), v(1080, "vp9", "video/webm"), a("mp4a.40.2", "audio/mp4", 128_000),
        ))
        assertTrue(q.isEmpty())
    }

    @Test fun withoutAacOnlyCombinedStreams() {
        val q = YouTubeFormats.qualities(listOf(v(360, "avc1", audio = true), v(720, "avc1"), a("opus", "audio/webm", 160_000)))
        assertEquals(listOf("360p"), q.map { it.label })
    }

    @Test fun unknownSizeIsMinusOne() {
        val q = YouTubeFormats.qualities(listOf(v(720, "avc1", len = -1), a("mp4a.40.2", "audio/mp4", 128_000)))
        assertEquals(-1, q.single().approxBytes)
    }

    @Test fun defaultChoice() {
        val q = YouTubeFormats.qualities(typical)
        assertEquals("720p60", YouTubeFormats.defaultChoice(q)!!.label)
        assertEquals("144p", YouTubeFormats.defaultChoice(q, preferHeight = 100)!!.label)
        assertNull(YouTubeFormats.defaultChoice(emptyList()))
    }
}
