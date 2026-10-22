package com.storysaver.app.share

import com.storysaver.app.util.UrlCheck
import com.storysaver.app.util.UrlValidator
import com.storysaver.app.youtube.YouTubeLinks
import com.storysaver.app.youtube.YtLink

/** What a piece of shared text is, so the share window knows what to show. Pure, unit-tested. */
sealed interface ShareTarget {
    data class Instagram(val check: UrlCheck) : ShareTarget
    data class YouTube(val link: YtLink.Video) : ShareTarget
    /** Recognised, but can't be downloaded; [message] says why and what to do instead. */
    data class Unsupported(val message: String) : ShareTarget
}

object ShareRouter {

    fun route(text: String): ShareTarget {
        when (val yt = YouTubeLinks.parse(text)) {
            is YtLink.Video -> return ShareTarget.YouTube(yt)
            is YtLink.NotAVideo -> return ShareTarget.Unsupported(
                when (yt.what) {
                    "playlist" -> "That's a YouTube playlist. Share a single video instead."
                    "channel" -> "That's a YouTube channel. Share a single video instead."
                    else -> "That YouTube link isn't a video. Open the video and share it from there."
                }
            )
            null -> Unit
        }
        return when (val ig = UrlValidator.check(text)) {
            is UrlCheck.Post, is UrlCheck.ShareLink, is UrlCheck.DirectMedia -> ShareTarget.Instagram(ig)
            is UrlCheck.StoryPage -> ShareTarget.Unsupported(
                "Instagram Stories can't be downloaded from a link — Instagram only shows them to logged-in users. " +
                    "Reels and posts from public accounts work."
            )
            UrlCheck.OtherInstagramPage -> ShareTarget.Unsupported(
                "That Instagram link isn't a post or reel. Share the specific reel or post instead."
            )
            UrlCheck.NotInstagram, is UrlCheck.Invalid -> ShareTarget.Unsupported(
                "Story Saver can download Instagram reels and posts and YouTube videos. " +
                    "This link isn't one of those."
            )
        }
    }
}
