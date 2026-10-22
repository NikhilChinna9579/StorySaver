package com.storysaver.app.youtube

import java.net.URI

sealed interface YtLink {
    data class Video(val id: String, val kind: String) : YtLink {
        val canonicalUrl: String get() = "https://www.youtube.com/watch?v=$id"
    }

    /** A playlist / channel / search link: not a single video. */
    data class NotAVideo(val what: String) : YtLink
}

/** Pure parsing of YouTube links (unit-tested). */
object YouTubeLinks {

    private val idRegex = Regex("^[A-Za-z0-9_-]{11}$")
    private val urlRegex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    private val youtubeHosts = setOf(
        "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com",
    )

    /** Returns null when the text isn't a YouTube link at all. */
    fun parse(input: String): YtLink? {
        var text = input.trim()
        if (text.isEmpty()) return null
        if (text.any { it.isWhitespace() }) text = urlRegex.find(text)?.value ?: return null
        text = text.trimEnd('.', ',', ')', ']', '!')
        if (!text.contains("://")) text = "https://$text"
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
        val segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val query = parseQuery(uri.rawQuery)

        if (host == "youtu.be" || host == "www.youtu.be") {
            val id = segments.firstOrNull()?.takeIf { idRegex.matches(it) } ?: return YtLink.NotAVideo("link")
            return YtLink.Video(id, "video")
        }
        if (host !in youtubeHosts) return null

        when (segments.firstOrNull()?.lowercase()) {
            null, "watch" -> {
                val v = query["v"]
                if (v != null && idRegex.matches(v)) return YtLink.Video(v, "video")
                if (query.containsKey("list")) return YtLink.NotAVideo("playlist")
                return YtLink.NotAVideo("page")
            }
            "shorts" -> return idAt(segments, 1, "short")
            "live" -> return idAt(segments, 1, "live")
            "embed", "v", "e" -> return idAt(segments, 1, "video")
            "playlist" -> return YtLink.NotAVideo("playlist")
            "results" -> return YtLink.NotAVideo("search")
            "channel", "c", "user" -> return YtLink.NotAVideo("channel")
        }
        if (segments.first().startsWith("@")) return YtLink.NotAVideo("channel")
        return YtLink.NotAVideo("page")
    }

    private fun idAt(segments: List<String>, i: Int, kind: String): YtLink {
        val id = segments.getOrNull(i)?.takeIf { idRegex.matches(it) } ?: return YtLink.NotAVideo("link")
        return YtLink.Video(id, kind)
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        return raw.split('&').mapNotNull {
            val k = it.substringBefore('=')
            val raw = it.substringAfter('=', "")
            if (k.isEmpty()) null else k to (runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrNull() ?: raw)
        }.toMap()
    }
}
