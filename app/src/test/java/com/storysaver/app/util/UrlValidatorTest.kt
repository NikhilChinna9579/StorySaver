package com.storysaver.app.util

import com.storysaver.app.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlValidatorTest {

    @Test fun emptyInputIsInvalid() {
        assertTrue(UrlValidator.check("   ") is UrlCheck.Invalid)
    }

    @Test fun garbageIsInvalid() {
        assertTrue(UrlValidator.check("not a link at all") is UrlCheck.Invalid)
    }

    @Test fun nonWebSchemeIsInvalid() {
        assertTrue(UrlValidator.check("ftp://instagram.com/stories/a/1") is UrlCheck.Invalid)
    }

    @Test fun storyUrlIsRecognisedAsPage() {
        val r = UrlValidator.check("https://www.instagram.com/stories/some.user/3141592653589793/?igsh=abc")
        assertTrue(r is UrlCheck.StoryPage)
        assertEquals("some.user", (r as UrlCheck.StoryPage).username)
    }

    @Test fun highlightUrlIsStoryPageWithoutUsername() {
        val r = UrlValidator.check("https://instagram.com/stories/highlights/1789/")
        assertTrue(r is UrlCheck.StoryPage)
        assertNull((r as UrlCheck.StoryPage).username)
    }

    @Test fun schemeIsAddedWhenMissing() {
        assertTrue(UrlValidator.check("instagram.com/stories/user/123") is UrlCheck.StoryPage)
    }

    @Test fun postAndReelAreRecognised() {
        assertEquals(UrlCheck.Post("AbC123", "post"), UrlValidator.check("https://www.instagram.com/p/AbC123/"))
        assertEquals(UrlCheck.Post("AbC123", "reel"), UrlValidator.check("https://www.instagram.com/reel/AbC123/"))
        assertEquals(UrlCheck.Post("AbC123", "reel"), UrlValidator.check("https://www.instagram.com/reels/AbC123"))
        assertEquals(UrlCheck.Post("AbC123", "video"), UrlValidator.check("https://instagram.com/tv/AbC123/"))
    }

    /** The exact link format from Instagram's Share → Copy link (the user's real example). */
    @Test fun copiedReelLinkWithTrackingParams() {
        assertEquals(
            UrlCheck.Post("DeyQf_PzHlD", "reel"),
            UrlValidator.check("https://www.instagram.com/reel/DeyQf_PzHlD/?exln=dmcxMDJ0YmxzMnFn"),
        )
        assertEquals(
            UrlCheck.Post("DeyQf_PzHlD", "reel"),
            UrlValidator.check("https://www.instagram.com/reel/DeyQf_PzHlD/?igsh=MWQ1ZGUxMzBkMA=="),
        )
        // Pasted together with share text, on mobile host, without scheme
        assertEquals(UrlCheck.Post("DeyQf_PzHlD", "reel"),
            UrlValidator.check("Watch this! https://www.instagram.com/reel/DeyQf_PzHlD/?utm_source=ig_web_copy_link"))
        assertEquals(UrlCheck.Post("C9xYz_12AB", "post"), UrlValidator.check("https://m.instagram.com/p/C9xYz_12AB/"))
        assertEquals(UrlCheck.Post("C9xYz_12AB", "post"), UrlValidator.check("instagram.com/p/C9xYz_12AB"))
    }

    @Test fun usernamePrefixedAndShareLinks() {
        assertEquals(UrlCheck.Post("C9xYz_12AB", "post"), UrlValidator.check("https://www.instagram.com/some.user/p/C9xYz_12AB/"))
        assertEquals(UrlCheck.Post("C9xYz_12AB", "reel"), UrlValidator.check("https://www.instagram.com/some.user/reel/C9xYz_12AB/"))
        val share = UrlValidator.check("https://www.instagram.com/share/reel/BAkd9fzXyZ")
        assertTrue(share is UrlCheck.ShareLink)
        assertTrue(UrlValidator.check("https://www.instagram.com/share/") is UrlCheck.OtherInstagramPage)
    }

    @Test fun badShortcodesAreNotPosts() {
        assertEquals(UrlCheck.OtherInstagramPage, UrlValidator.check("https://www.instagram.com/p/"))
        assertEquals(UrlCheck.OtherInstagramPage, UrlValidator.check("https://www.instagram.com/p/a!b/"))
    }

    @Test fun profileIsOtherPage() {
        assertEquals(UrlCheck.OtherInstagramPage, UrlValidator.check("https://www.instagram.com/someone/"))
    }

    @Test fun cdnImageIsDirectMedia() {
        val r = UrlValidator.check("https://scontent-bom1-1.cdninstagram.com/v/t51.2885-15/12345_n.jpg?stp=dst-jpg&_nc_ht=x")
        assertTrue(r is UrlCheck.DirectMedia)
        assertEquals(MediaType.IMAGE, (r as UrlCheck.DirectMedia).guessedType)
    }

    @Test fun fbcdnVideoIsDirectMedia() {
        val r = UrlValidator.check("https://video.xx.fbcdn.net/v/t42.1790-2/abc.mp4?_nc_cat=1")
        assertEquals(MediaType.VIDEO, (r as UrlCheck.DirectMedia).guessedType)
    }

    @Test fun cdnWithoutExtensionHasUnknownType() {
        val r = UrlValidator.check("https://scontent.cdninstagram.com/v/t51/abcdef")
        assertNull((r as UrlCheck.DirectMedia).guessedType)
    }

    @Test fun plainHttpCdnIsRejected() {
        assertTrue(UrlValidator.check("http://scontent.cdninstagram.com/x.jpg") is UrlCheck.Invalid)
    }

    @Test fun lookalikeHostsAreNotInstagram() {
        assertEquals(UrlCheck.NotInstagram, UrlValidator.check("https://instagram.com.evil.example/stories/a/1"))
        assertEquals(UrlCheck.NotInstagram, UrlValidator.check("https://evilcdninstagram.com/a.jpg"))
        assertEquals(UrlCheck.NotInstagram, UrlValidator.check("https://example.com/photo.jpg"))
    }

    @Test fun extractsUrlFromShareText() {
        val text = "Look at this story! https://www.instagram.com/stories/user/123/?igsh=xyz."
        assertEquals("https://www.instagram.com/stories/user/123/?igsh=xyz", UrlValidator.extractFirstUrl(text))
        assertTrue(UrlValidator.check(text) is UrlCheck.StoryPage)
    }

    @Test fun looksLikeUrlDetection() {
        assertTrue(UrlValidator.looksLikeUrl("https://wa.me/123"))
        assertTrue(UrlValidator.looksLikeUrl("example.com/status"))
        assertTrue(!UrlValidator.looksLikeUrl("hello there"))
    }
}
