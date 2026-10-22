package com.storysaver.app.instagram

import com.storysaver.app.model.MediaType
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** One downloadable image or video inside a post (a carousel has several). */
data class IgMediaItem(
    val index: Int,
    val type: MediaType,
    val url: String,
    val thumbnailUrl: String?,
)

/** A public post / reel resolved to its media files. */
data class IgPost(
    val shortcode: String?,
    val owner: String?,
    val items: List<IgMediaItem>,
)

sealed interface IgParse {
    data class Found(val post: IgPost) : IgParse

    /** The page loaded but says the post can't be shown (private, deleted, or embedding off). */
    data class Unavailable(val reason: String) : IgParse

    /** The page didn't contain media data in a format we understand. */
    data object NoData : IgParse
}

/**
 * Pure parsing of Instagram's public responses (no Android, no network — unit-tested).
 *
 * Sources:
 *  - the public embed page `instagram.com/p/<code>/embed/captioned/`, which carries a
 *    `contextJSON` string with `gql_data.shortcode_media`;
 *  - the web GraphQL response, which carries `data.xdt_shortcode_media`.
 * Both use the same `shortcode_media` shape.
 */
object InstagramParser {

    private const val CONTEXT_KEY = "\"contextJSON\":"

    fun parseEmbed(html: String): IgParse {
        val contextText = extractContextJson(html)
        if (contextText != null) {
            val ctx = runCatching { JSONObject(contextText) }.getOrNull()
            val media = ctx?.optJSONObject("gql_data")?.optJSONObject("shortcode_media")
            if (media != null) {
                return fromShortcodeMedia(media)?.let { IgParse.Found(it) }
                    ?: IgParse.Unavailable(
                        "Instagram doesn't make this post's media available outside the app " +
                            "(this happens with some music-licensed reels)."
                    )
            }
            // contextJSON present but no media → the embed is disabled or the post is private/removed.
            return IgParse.Unavailable(UNAVAILABLE)
        }
        if (html.contains("EmbedIsBroken") || html.contains("EmbedBrokenMedia")) {
            return IgParse.Unavailable(UNAVAILABLE)
        }
        return IgParse.NoData
    }

    fun parseGraphql(json: String): IgParse {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return IgParse.NoData
        val data = root.optJSONObject("data") ?: return IgParse.NoData
        val media = data.optJSONObject("xdt_shortcode_media") ?: data.optJSONObject("shortcode_media")
        if (media == null) {
            return if (data.has("xdt_shortcode_media") || data.has("shortcode_media")) {
                IgParse.Unavailable(UNAVAILABLE) // key present but null: private or deleted
            } else {
                IgParse.NoData
            }
        }
        return fromShortcodeMedia(media)?.let { IgParse.Found(it) } ?: IgParse.NoData
    }

    /** Converts a `shortcode_media` object into a post; null if it has no downloadable media. */
    fun fromShortcodeMedia(media: JSONObject): IgPost? {
        val shortcode = media.optStringOrNull("shortcode")
        val owner = media.optJSONObject("owner")?.optStringOrNull("username")
        val children = media.optJSONObject("edge_sidecar_to_children")?.optJSONArray("edges")
        val nodes: List<JSONObject> = if (children != null && children.length() > 0) {
            children.objects().mapNotNull { it.optJSONObject("node") }
        } else {
            listOf(media)
        }
        val items = nodes.mapIndexedNotNull { i, node -> itemOf(i, node) }
        if (items.isEmpty()) return null
        return IgPost(shortcode, owner, items.mapIndexed { i, it -> it.copy(index = i) })
    }

    private fun itemOf(index: Int, node: JSONObject): IgMediaItem? {
        val thumb = node.optStringOrNull("display_url") ?: largestDisplayResource(node)
        val isVideo = node.optBoolean("is_video", false) ||
            node.optStringOrNull("__typename")?.contains("Video") == true
        return if (isVideo) {
            val video = node.optStringOrNull("video_url") ?: return null
            IgMediaItem(index, MediaType.VIDEO, video, thumb)
        } else {
            val image = thumb ?: return null
            IgMediaItem(index, MediaType.IMAGE, image, image)
        }
    }

    private fun largestDisplayResource(node: JSONObject): String? {
        val arr = node.optJSONArray("display_resources") ?: return null
        return arr.objects().maxByOrNull { it.optInt("config_width", 0) }?.optStringOrNull("src")
    }

    /**
     * Finds `"contextJSON":"…"` and returns the decoded inner JSON text.
     * Scans by hand: the value is ~25 KB and Java's regex engine can overflow the stack on
     * alternation loops over strings that long.
     */
    internal fun extractContextJson(html: String): String? {
        var from = 0
        while (true) {
            val at = html.indexOf(CONTEXT_KEY, from)
            if (at < 0) return null
            var i = at + CONTEXT_KEY.length
            while (i < html.length && html[i].isWhitespace()) i++
            if (i >= html.length) return null
            if (html[i] != '"') { // e.g. "contextJSON":null
                from = i
                continue
            }
            val start = i
            i++
            while (i < html.length) {
                when (html[i]) {
                    '\\' -> i += 2
                    '"' -> break
                    else -> i++
                }
            }
            if (i >= html.length) return null
            val literal = html.substring(start, i + 1)
            val decoded = runCatching { JSONTokener(literal).nextValue() as? String }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
            from = i + 1
        }
    }

    private fun JSONArray.objects(): List<JSONObject> =
        (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").ifBlank { null }

    const val UNAVAILABLE =
        "Instagram didn't return this post. It may be from a private account, deleted, " +
            "age-restricted, or its owner turned off sharing outside Instagram."
}
