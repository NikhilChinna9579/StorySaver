package com.storysaver.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real network: a short public YouTube video, found, downloaded, merged and verified on-device. */
@RunWith(AndroidJUnit4::class)
class YouTubeE2ETest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val link = "https://youtu.be/jNQXAC9IVRw?si=e2e" // "Me at the zoo", 19 s

    private val qualityRow = SemanticsMatcher("quality row") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("yt_q_") == true
    }

    private fun findVideo() {
        rule.openSource("Video", "YouTube")
        rule.waitUntil(10_000) { rule.hasTag("yt_url") }
        rule.onNodeWithTag("yt_url").performTextInput(link)
        rule.onNodeWithTag("yt_find").performClick()
        rule.waitUntil(90_000) { rule.hasTag("yt_download") || rule.hasText("Couldn't get this video") }
        Shots.take("40_youtube_found")
        // YouTube refuses anonymous access from data-centre IPs (like CI). That's a network block,
        // not an app failure — skip with a clear reason; on phones this path works normally.
        assumeFalse("YouTube bot-check on this network", rule.hasText("confirm it's not a bot", substring = true))
        if (!rule.hasTag("yt_download")) {
            val msg = rule.onAllNodesWithText("", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
                .joinToString(" | ") { it.config.toString() }.take(1500)
            fail("YouTube video was not resolved. Screen: $msg")
        }
    }

    private fun downloadAndVerify(shot: String): Int {
        val before = savedIds()
        rule.onNodeWithTag("yt_download").performScrollTo().performClick()
        rule.waitUntil(240_000) { rule.hasTag("yt_saved") || rule.hasText("Download failed") }
        Shots.take(shot)
        assertTrue("YouTube download did not complete", rule.hasTag("yt_saved"))
        val added = savedIds() - before
        assertEquals("exactly one new YouTube file", 1, added.size)
        return tracks(added.single())
    }

    @Test fun lowestResolutionIsDownloadedAndMerged() {
        findVideo()
        // Lowest option: on YouTube these are video-only streams, so this exercises the merge.
        rule.onAllNodes(qualityRow).onLast().performScrollTo().performClick()
        val tracks = downloadAndVerify("41_youtube_saved_lowest")
        assertEquals("saved file must contain video AND audio", 2, tracks)
    }

    @Test fun defaultResolutionIsDownloaded() {
        findVideo()
        val tracks = downloadAndVerify("42_youtube_saved_default")
        assertEquals("saved file must contain video AND audio", 2, tracks)
    }

    private fun savedIds(): Set<Long> {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        return resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("StorySaver_YT_%"), null,
        )?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptySet()
    }

    /** Number of distinct audio/video tracks (1 video + 1 audio = 2). */
    private fun tracks(id: Long): Int {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            val mimes = (0 until ex.trackCount).mapNotNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
            val hasVideo = mimes.any { it.startsWith("video/") }
            val hasAudio = mimes.any { it.startsWith("audio/") }
            return (if (hasVideo) 1 else 0) + (if (hasAudio) 1 else 0)
        } finally {
            ex.release()
        }
    }
}
