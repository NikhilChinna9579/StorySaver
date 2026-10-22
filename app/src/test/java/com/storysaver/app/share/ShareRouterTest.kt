package com.storysaver.app.share

import com.storysaver.app.util.MimeUtils
import com.storysaver.app.util.UrlCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareRouterTest {

    @Test fun instagramShareText() {
        val r = ShareRouter.route("https://www.instagram.com/reel/DeyQf_PzHlD/?igsh=MWQ1ZGUx")
        assertEquals(UrlCheck.Post("DeyQf_PzHlD", "reel"), (r as ShareTarget.Instagram).check)
        assertTrue(ShareRouter.route("https://www.instagram.com/p/C9xYz_12AB/") is ShareTarget.Instagram)
        assertTrue(ShareRouter.route("https://www.instagram.com/share/reel/BAkd9fzXyZ") is ShareTarget.Instagram)
    }

    @Test fun youtubeShareText() {
        // What the YouTube app puts in the share sheet
        val r = ShareRouter.route("https://youtu.be/dQw4w9WgXcQ?si=AbCdEf12")
        assertEquals("dQw4w9WgXcQ", (r as ShareTarget.YouTube).link.id)
        assertTrue(ShareRouter.route("Watch: https://youtube.com/shorts/dQw4w9WgXcQ?feature=share") is ShareTarget.YouTube)
    }

    @Test fun unsupportedExplained() {
        listOf(
            "https://www.instagram.com/stories/user/123/",
            "https://www.instagram.com/someone/",
            "https://www.youtube.com/playlist?list=PL1",
            "https://example.com/x",
            "just some text",
        ).forEach { assertTrue(it, ShareRouter.route(it) is ShareTarget.Unsupported) }
    }

    @Test fun audioMimeHelpers() {
        assertEquals("m4a", MimeUtils.extensionForMime("audio/mp4"))
        assertEquals("mp3", MimeUtils.extensionForMime("audio/mpeg"))
        assertEquals("m4a", MimeUtils.extensionForMime("audio/x-unknown"))
        assertTrue(MimeUtils.isAudio("audio/mp4; codecs=mp4a.40.2"))
        assertTrue(MimeUtils.isSavable("audio/mp4") && MimeUtils.isSavable("video/mp4") && MimeUtils.isSavable("image/png"))
        assertTrue(!MimeUtils.isSavable("text/html"))
    }
}
