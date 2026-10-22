package com.storysaver.app.util

import com.storysaver.app.model.MediaType
import java.net.URI
import java.net.URISyntaxException

/** Result of classifying a pasted link. Each case maps to a distinct UI message. */
sealed interface UrlCheck {
    /** A direct file on Instagram's media CDN — the only kind we download. */
    data class DirectMedia(val url: String, val guessedType: MediaType?) : UrlCheck

    /** instagram.com/stories/... — a page, not a media file. */
    data class StoryPage(val username: String?) : UrlCheck

    /** instagram.com/p/, /reel/, /tv/ — a page, not a media file. */
    data class PostPage(val kind: String) : UrlCheck

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

        if (host in instagramHosts) {
            val segments = (uri.path ?: "").split('/').filter { it.isNotEmpty() }
            return when (segments.firstOrNull()?.lowercase()) {
                "stories" -> UrlCheck.StoryPage(
                    segments.getOrNull(1)?.takeUnless { it.equals("highlights", ignoreCase = true) }
                )
                "s" -> UrlCheck.StoryPage(null) // shortened story/highlight share link
                "p" -> UrlCheck.PostPage("post")
                "reel", "reels" -> UrlCheck.PostPage("reel")
                "tv" -> UrlCheck.PostPage("video")
                else -> UrlCheck.OtherInstagramPage
            }
        }

        if (cdnDomains.any { host == it || host.endsWith(".$it") }) {
            if (scheme != "https") {
                return UrlCheck.Invalid("Only secure (https) media links are supported.")
            }
            return UrlCheck.DirectMedia(uri.toString(), MimeUtils.typeFromPath(uri.path ?: ""))
        }

        return UrlCheck.NotInstagram
    }
}
