package com.storysaver.app.util

import com.storysaver.app.model.MediaType
import java.net.URI
import java.net.URISyntaxException

/** Result of classifying a pasted link. Each case maps to a distinct UI path or message. */
sealed interface UrlCheck {
    /** A direct file on Instagram's media CDN. */
    data class DirectMedia(val url: String, val guessedType: MediaType?) : UrlCheck

    /** A public reel / post / IGTV link, identified by its shortcode. */
    data class Post(val shortcode: String, val kind: String) : UrlCheck

    /** instagram.com/share/... short link that redirects to a post or reel. */
    data class ShareLink(val url: String) : UrlCheck

    /** instagram.com/stories/... — only viewable inside Instagram, never public. */
    data class StoryPage(val username: String?) : UrlCheck

    /** Any other instagram.com page (profile, explore, ...). */
    data object OtherInstagramPage : UrlCheck

    /** A valid link, but not to Instagram or its CDN. */
    data object NotInstagram : UrlCheck

    /** Not a usable link at all. */
    data class Invalid(val reason: String) : UrlCheck
}

object UrlValidator {

    private val instagramHosts = setOf(
        "instagram.com", "www.instagram.com", "m.instagram.com",
        "instagr.am", "www.instagr.am",
    )

    /** Instagram media is served from these CDN domains (and their subdomains). */
    private val cdnDomains = listOf("cdninstagram.com", "fbcdn.net")

    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val shortcodeRegex = Regex("^[A-Za-z0-9_-]{5,64}$")

    private val postKinds = mapOf(
        "p" to "post", "reel" to "reel", "reels" to "reel", "tv" to "video",
    )

    /** Pulls the first http(s) URL out of share text like "Check this out https://…". */
    fun extractFirstUrl(text: String): String? =
        urlRegex.find(text)?.value?.trimEnd('.', ',', ')', ']', '!', '?')

    fun looksLikeUrl(text: String): Boolean {
        val t = text.trim()
        return extractFirstUrl(t) != null || Regex("""^[\w-]+(\.[\w-]+)+(/\S*)?$""").matches(t)
    }

    fun check(input: String): UrlCheck {
        var text = input.trim()
        if (text.isEmpty()) return UrlCheck.Invalid("Paste a link first.")
        if (text.any { it.isWhitespace() }) {
            text = extractFirstUrl(text) ?: return UrlCheck.Invalid("That doesn't look like a link.")
        }
        if (!text.contains("://")) text = "https://$text"

        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return UrlCheck.Invalid("That doesn't look like a valid link.")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") {
            return UrlCheck.Invalid("Only web links (https://…) are supported.")
        }
        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: return UrlCheck.Invalid("The link is missing a website address.")

        if (host in instagramHosts) return classifyInstagramPath(uri, text)

        if (cdnDomains.any { host == it || host.endsWith(".$it") }) {
            if (scheme != "https") {
                return UrlCheck.Invalid("Only secure (https) media links are supported.")
            }
            return UrlCheck.DirectMedia(uri.toString(), MimeUtils.typeFromPath(uri.path ?: ""))
        }

        return UrlCheck.NotInstagram
    }

    private fun classifyInstagramPath(uri: URI, original: String): UrlCheck {
        val segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
        val first = segments.firstOrNull()?.lowercase() ?: return UrlCheck.OtherInstagramPage

        when (first) {
            "stories" -> return UrlCheck.StoryPage(
                segments.getOrNull(1)?.takeUnless { it.equals("highlights", ignoreCase = true) }
            )
            "s" -> return UrlCheck.StoryPage(null) // shortened story / highlight share link
            "share" -> {
                // instagram.com/share/reel/<id>, /share/p/<id>, /share/<id>: redirects to the real post
                return if (segments.size >= 2) UrlCheck.ShareLink(httpsOf(original)) else UrlCheck.OtherInstagramPage
            }
        }

        // instagram.com/p/<code>, /reel/<code>, /reels/<code>, /tv/<code>
        postAt(segments, 0)?.let { return it }
        // instagram.com/<username>/p/<code> or /<username>/reel/<code> (newer share format)
        postAt(segments, 1)?.let { return it }

        return UrlCheck.OtherInstagramPage
    }

    private fun postAt(segments: List<String>, index: Int): UrlCheck.Post? {
        val kind = postKinds[segments.getOrNull(index)?.lowercase()] ?: return null
        val code = segments.getOrNull(index + 1) ?: return null
        return if (shortcodeRegex.matches(code)) UrlCheck.Post(code, kind) else null
    }

    private fun httpsOf(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url
}
