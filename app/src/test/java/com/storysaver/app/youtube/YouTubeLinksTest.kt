package com.storysaver.app.youtube

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLinksTest {

    private fun id(s: String) = (YouTubeLinks.parse(s) as? YtLink.Video)?.id

    @Test fun allCommonVideoLinkShapes() {
        val id = "dQw4w9WgXcQ"
        listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&t=42s&list=PL123",
            "https://m.youtube.com/watch?feature=share&v=$id",
            "https://music.youtube.com/watch?v=$id&si=abc",
            "https://youtu.be/$id",
            "https://youtu.be/$id?si=Xyz_12-ab&t=10",          // YouTube app "Share → Copy link"
            "youtu.be/$id",                                     // no scheme
            "https://www.youtube.com/shorts/$id?feature=share",
            "https://youtube.com/shorts/$id",
            "https://www.youtube.com/live/$id?si=x",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube-nocookie.com/embed/$id?rel=0",
            "Check this out: https://youtu.be/$id?si=abc",      // share text
            "http://www.youtube.com/watch?v=$id",
        ).forEach { assertEquals(it, id, id(it)) }
    }

    @Test fun kindsAndCanonicalUrl() {
        val shorts = YouTubeLinks.parse("https://youtube.com/shorts/abcdefghijk") as YtLink.Video
        assertEquals("short", shorts.kind)
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk", shorts.canonicalUrl)
    }

    @Test fun notSingleVideos() {
        assertEquals(YtLink.NotAVideo("playlist"), YouTubeLinks.parse("https://www.youtube.com/playlist?list=PL123"))
        assertEquals(YtLink.NotAVideo("playlist"), YouTubeLinks.parse("https://www.youtube.com/watch?list=PL123"))
        assertEquals(YtLink.NotAVideo("channel"), YouTubeLinks.parse("https://www.youtube.com/@SomeCreator"))
        assertEquals(YtLink.NotAVideo("channel"), YouTubeLinks.parse("https://www.youtube.com/channel/UC123"))
        assertEquals(YtLink.NotAVideo("search"), YouTubeLinks.parse("https://www.youtube.com/results?search_query=x"))
        assertTrue(YouTubeLinks.parse("https://www.youtube.com/") is YtLink.NotAVideo)
        assertTrue(YouTubeLinks.parse("https://youtu.be/short") is YtLink.NotAVideo) // bad id length
        assertTrue(YouTubeLinks.parse("https://www.youtube.com/watch?v=%ZZ") !is YtLink.Video) // malformed escape
    }

    @Test fun nonYouTubeIsNull() {
        assertNull(YouTubeLinks.parse("https://www.instagram.com/reel/DeyQf_PzHlD/"))
        assertNull(YouTubeLinks.parse("https://notyoutube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeLinks.parse("https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeLinks.parse("hello"))
        assertNull(YouTubeLinks.parse(""))
    }
}
