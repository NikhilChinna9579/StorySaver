package com.storysaver.app.instagram

import com.storysaver.app.model.MediaType
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramParserTest {

    private val videoUrl = "https://scontent-dfw6-1.cdninstagram.com/o1/v/t2/f2/m86/AQN.mp4?_nc_cat=1&oh=abc&oe=def"
    private val imageUrl = "https://scontent-dfw6-1.cdninstagram.com/v/t51.2885-15/123_n.jpg?stp=dst-jpg&_nc_ht=x"

    private fun videoNode() = JSONObject()
        .put("__typename", "GraphVideo").put("shortcode", "DeyQf_PzHlD").put("is_video", true)
        .put("video_url", videoUrl).put("display_url", imageUrl).put("product_type", "clips")
        .put("owner", JSONObject().put("username", "public.creator"))

    private fun imageNode(url: String = imageUrl) = JSONObject()
        .put("__typename", "GraphImage").put("is_video", false).put("display_url", url)

    /** Wraps shortcode_media the way the real embed page does: JSON inside a JS string inside HTML. */
    private fun embedHtml(media: JSONObject?, padding: Int = 0): String {
        val ctx = JSONObject()
            .put("context", JSONObject().put("type", media?.optString("__typename") ?: "Unknown"))
            .put("gql_data", if (media == null) JSONObject.NULL else JSONObject().put("shortcode_media", media))
        val literal = JSONObject.quote(ctx.toString()) // escapes quotes and slashes like Instagram does
        return "<html><script>" + "x".repeat(padding) +
            "{\"isRichEmbed\":true,\"contextJSON\":null},{\"isRichEmbed\":true,\"isSidecar\":false,\"contextJSON\":" +
            literal + "}]]</script></html>"
    }

    @Test fun parsesSingleVideoReel() {
        val r = InstagramParser.parseEmbed(embedHtml(videoNode()))
        assertTrue(r is IgParse.Found)
        val post = (r as IgParse.Found).post
        assertEquals("DeyQf_PzHlD", post.shortcode)
        assertEquals("public.creator", post.owner)
        assertEquals(1, post.items.size)
        assertEquals(MediaType.VIDEO, post.items[0].type)
        assertEquals(videoUrl, post.items[0].url) // unescaped: "\/" → "/", "&" intact
        assertEquals(imageUrl, post.items[0].thumbnailUrl)
    }

    @Test fun parsesSingleImagePost() {
        val post = (InstagramParser.parseEmbed(embedHtml(imageNode())) as IgParse.Found).post
        assertEquals(MediaType.IMAGE, post.items.single().type)
        assertEquals(imageUrl, post.items.single().url)
    }

    @Test fun parsesCarouselInOrder() {
        val edges = JSONArray()
            .put(JSONObject().put("node", imageNode("$imageUrl&n=1")))
            .put(JSONObject().put("node", videoNode()))
            .put(JSONObject().put("node", imageNode("$imageUrl&n=3")))
        val sidecar = JSONObject().put("__typename", "GraphSidecar").put("shortcode", "CAR0USEL1")
            .put("display_url", imageUrl)
            .put("edge_sidecar_to_children", JSONObject().put("edges", edges))
        val post = (InstagramParser.parseEmbed(embedHtml(sidecar)) as IgParse.Found).post
        assertEquals(listOf(MediaType.IMAGE, MediaType.VIDEO, MediaType.IMAGE), post.items.map { it.type })
        assertEquals(listOf(0, 1, 2), post.items.map { it.index })
        assertTrue(post.items[2].url.endsWith("&n=3"))
    }

    @Test fun largeContextJsonDoesNotOverflow() {
        // Real pages are ~200 KB with a ~25 KB contextJSON; make sure scanning is not regex-recursive.
        val big = videoNode().put("edge_media_to_caption", "y".repeat(200_000))
        assertTrue(InstagramParser.parseEmbed(embedHtml(big, padding = 300_000)) is IgParse.Found)
    }

    @Test fun nullMediaMeansUnavailable() {
        assertTrue(InstagramParser.parseEmbed(embedHtml(null)) is IgParse.Unavailable)
    }

    @Test fun videoWithoutUrlIsUnavailable() {
        val node = videoNode().apply { remove("video_url") }
        assertTrue(InstagramParser.parseEmbed(embedHtml(node)) is IgParse.Unavailable)
    }

    @Test fun javascriptOnlyPageIsNoData() {
        // What a browser user agent gets: a JS app shell with no contextJSON.
        assertEquals(IgParse.NoData, InstagramParser.parseEmbed("<html><script src=a.js></script>login login</html>"))
        assertTrue(InstagramParser.parseEmbed("<div class=\"EmbedIsBroken\"></div>") is IgParse.Unavailable)
    }

    @Test fun parsesGraphqlResponse() {
        val ok = JSONObject().put("data", JSONObject().put("xdt_shortcode_media", videoNode())).toString()
        assertEquals(MediaType.VIDEO, (InstagramParser.parseGraphql(ok) as IgParse.Found).post.items.single().type)

        val gone = JSONObject().put("data", JSONObject().put("xdt_shortcode_media", JSONObject.NULL)).toString()
        assertTrue(InstagramParser.parseGraphql(gone) is IgParse.Unavailable)

        val loginWall = """{"message":"Please wait a few minutes before you try again.","require_login":true,"status":"fail"}"""
        assertEquals(IgParse.NoData, InstagramParser.parseGraphql(loginWall))
        assertEquals(IgParse.NoData, InstagramParser.parseGraphql("<html>not json</html>"))
    }

    @Test fun displayResourcesFallback() {
        val node = JSONObject().put("is_video", false).put("display_resources", JSONArray()
            .put(JSONObject().put("src", "https://x.cdninstagram.com/small.jpg").put("config_width", 320))
            .put(JSONObject().put("src", "https://x.cdninstagram.com/big.jpg").put("config_width", 1080)))
        assertEquals("https://x.cdninstagram.com/big.jpg", InstagramParser.fromShortcodeMedia(node)!!.items.single().url)
        assertNull(InstagramParser.fromShortcodeMedia(JSONObject().put("is_video", false)))
    }

    @Test fun loginRedirectTargetIsRecovered() {
        assertEquals(
            "https://www.instagram.com/reel/DeyQf_PzHlD/",
            InstagramExtractor.loginNextTarget("https://www.instagram.com/accounts/login/?next=%2Freel%2FDeyQf_PzHlD%2F&is_from_rle"),
        )
        assertNull(InstagramExtractor.loginNextTarget("https://www.instagram.com/reel/DeyQf_PzHlD/"))
    }
}
