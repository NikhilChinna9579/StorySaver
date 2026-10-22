package com.storysaver.app

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.storysaver.app.media.TrimRange
import com.storysaver.app.share.ShareActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Trim option: opens only when tapped, and saves just the chosen part. Real Instagram reel. */
@RunWith(AndroidJUnit4::class)
class TrimE2ETest {

    @get:Rule val rule = createEmptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val reel = "https://www.instagram.com/reel/DeyQf_PzHlD/?igsh=trim"

    private fun shareText(text: String): ActivityScenario<ShareActivity> = ActivityScenario.launch(
        Intent(ctx, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    )

    private fun ms(tag: String): Long = TrimRange.parseClock(rule.textOf(tag)) ?: error("no time in $tag: '${rule.textOf(tag)}'")

    /** Share sheet → Trim → move both ends → only that part of the video is saved. */
    @Test fun shareSheetSavesOnlyTheChosenPart() {
        val before = MediaQueries.ids(MediaQueries.VIDEOS, "%clip%")
        val scenario = shareText(reel)
        rule.waitUntil(60_000) { rule.hasTag("qs_trim") || rule.hasTag("qs_unsupported") }
        assertTrue("Trim button missing", rule.hasTag("qs_trim"))
        assertTrue("normal download must still be there", rule.hasTag("qs_download"))
        assertFalse("trim screen must not open by itself", rule.hasTag("trim_dialog"))

        rule.onNodeWithTag("qs_trim").performClick()
        rule.waitForTrimScreen()
        Shots.take("90_trim_screen")
        val full = ms("trim_end")
        assumeTrue("reel too short to trim", full > 3_000)
        rule.tap("trim_start_plus", 2)  // start 1.0 s
        rule.tap("trim_end_minus", 2)   // end 1.0 s before the end
        val start = ms("trim_start")
        val end = ms("trim_end")
        assertEquals(1_000, start)
        assertTrue("end=$end full=$full", end in full - 1_100..full - 900)
        Shots.take("91_trim_selected")

        rule.onNodeWithTag("trim_confirm").performClick()
        rule.waitUntil(20_000) { rule.hasTag("qs_started") || rule.hasTag("qs_saved") }
        Shots.take("92_trim_started")
        scenario.close()

        val id = MediaQueries.waitForNew(MediaQueries.VIDEOS, "%clip%", before, 180_000)
        assertNotNull("clip never appeared", id)
        val uri = MediaQueries.uri(MediaQueries.VIDEOS, id!!)
        val name = MediaQueries.name(MediaQueries.VIDEOS, id)!!
        assertTrue(name, name.startsWith("ndtv_instagram_DeyQf_PzHlD_clip_0m01s-") && name.endsWith(".mp4"))
        val d = MediaQueries.durationMs(uri)
        val len = end - start
        // Starts at a keyframe at/before 1 s, so it can be a little longer than chosen — but the end is cut exactly.
        assertTrue("clip=$d chosen=$len full=$full", d in len - 400..len + 1_200 && d < full - 800)
        val mimes = MediaQueries.trackMimes(uri)
        assertTrue("tracks=$mimes", mimes.any { it.startsWith("video/") } && mimes.any { it.startsWith("audio/") })
    }

    /** Closing the trim screen changes nothing: the normal Download is still there and nothing started. */
    @Test fun closingTrimLeavesTheSheetAsItWas() {
        val scenario = shareText(reel)
        rule.waitUntil(60_000) { rule.hasTag("qs_trim") || rule.hasTag("qs_unsupported") }
        assumeTrue(rule.hasTag("qs_trim"))
        rule.onNodeWithTag("qs_trim").performClick()
        rule.waitUntil(10_000) { rule.hasTag("trim_dialog") }
        rule.onNodeWithTag("trim_close").performClick()
        rule.waitUntil(10_000) { !rule.hasTag("trim_dialog") }
        assertTrue(rule.hasTag("qs_download"))
        assertFalse("nothing should have started", rule.hasTag("qs_started"))
        rule.onNodeWithText("Download video").assertExists()
        scenario.close()
    }

    /** Instagram screen → Trim → Audio only → an exact-length M4A. */
    @Test fun instagramScreenAudioClip() {
        val before = MediaQueries.ids(MediaQueries.AUDIO, "%clip%")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.openSource("Video", "Instagram Story")
        rule.waitUntil(10_000) { rule.hasTag("ig_url") }
        rule.onNodeWithTag("ig_url").performTextInput(reel)
        rule.onNodeWithTag("ig_find").performClick()
        rule.waitUntil(60_000) { rule.hasTag("ig_trim_0") || rule.hasText("Couldn't get this post") }
        assertTrue("reel not resolved", rule.hasTag("ig_trim_0"))
        rule.onNodeWithTag("ig_trim_0").performScrollTo().performClick()
        rule.waitForTrimScreen()
        rule.onNodeWithTag("trim_opt_audio").performClick()
        rule.waitUntil(5_000) { rule.hasText("Trim audio") }
        val full = ms("trim_end")
        rule.tap("trim_start_plus", 2)  // 1.0 s
        rule.tap("trim_end_minus", 2)
        val len = ms("trim_end") - ms("trim_start")
        Shots.take("93_trim_audio")
        rule.onNodeWithTag("trim_confirm").performClick()

        val id = MediaQueries.waitForNew(MediaQueries.AUDIO, "%clip%", before, 180_000)
        assertNotNull("audio clip never appeared", id)
        val uri = MediaQueries.uri(MediaQueries.AUDIO, id!!)
        val name = MediaQueries.name(MediaQueries.AUDIO, id)!!
        assertTrue(name, name.startsWith("ndtv_instagram_DeyQf_PzHlD_audio_clip_0m01s-") && name.endsWith(".m4a"))
        assertEquals(listOf("audio/mp4a-latm"), MediaQueries.trackMimes(uri))
        val d = MediaQueries.durationMs(uri)
        assertTrue("clip=$d chosen=$len full=$full", d in len - 300..len + 300)
        rule.waitUntil(30_000) { rule.hasTag("ig_saved_0") }
        Shots.take("94_trim_audio_saved")
        scenario.close()
    }

    /** Downloads → open a saved video → Trim → Save clip makes a new, shorter copy (original kept). */
    @Test fun trimASavedVideoFromDownloads() {
        val videosBefore = MediaQueries.ids(MediaQueries.VIDEOS)
        assumeTrue("needs a saved video", videosBefore.isNotEmpty())
        val clipsBefore = MediaQueries.ids(MediaQueries.VIDEOS, "%clip%")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        rule.waitUntil(15_000) { rule.hasText("Downloads") }
        rule.onNodeWithText("Downloads").performClick()
        rule.waitUntil(10_000) { rule.hasTag("dl_summary") }
        rule.onNodeWithTag("dl_type_VIDEO").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("dl_row").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("dl_row").onFirst().performClick()
        rule.waitUntil(10_000) { rule.hasTag("viewer_trim") }
        rule.onNodeWithTag("viewer_trim").performClick()
        rule.waitForTrimScreen()
        val full = ms("trim_end")
        assumeTrue("video too short", full > 2_500)
        rule.tap("trim_start_plus")   // 0.5 s
        rule.tap("trim_end_minus")    // 0.5 s before the end
        val len = ms("trim_end") - ms("trim_start")
        Shots.take("95_trim_saved_video")
        rule.onNodeWithTag("trim_confirm").performClick()

        val id = MediaQueries.waitForNew(MediaQueries.VIDEOS, "%clip%", clipsBefore, 120_000)
        assertNotNull("clip of the saved video never appeared", id)
        val d = MediaQueries.durationMs(MediaQueries.uri(MediaQueries.VIDEOS, id!!))
        assertTrue("clip=$d chosen=$len full=$full", d in len - 400..len + 700 && d < full)
        val name = MediaQueries.name(MediaQueries.VIDEOS, id)!!
        assertTrue(name, name.contains("_clip_0m00s-") && !name.contains("("))
        assertTrue("original must be kept", (videosBefore - MediaQueries.ids(MediaQueries.VIDEOS)).isEmpty())
        scenario.close()
    }
}
