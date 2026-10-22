package com.storysaver.app

import android.content.Intent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.storysaver.app.share.ShareActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Share → Story Saver" from another app: the download sheet, end to end. */
@RunWith(AndroidJUnit4::class)
class ShareSheetE2ETest {

    @get:Rule val rule = createEmptyComposeRule()
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun shareText(text: String): ActivityScenario<ShareActivity> =
        ActivityScenario.launch(
            Intent(ctx, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text)
        )

    /** Reel shared from Instagram → Video window → Audio only → download keeps going after the sheet closes. */
    @Test fun instagramReelAudioOnlyCompletesInBackground() {
        val before = MediaQueries.ids(MediaQueries.AUDIO, "%instagram%")
        val scenario = shareText("https://www.instagram.com/reel/DeyQf_PzHlD/?igsh=e2e")
        rule.waitUntil(60_000) { rule.hasTag("qs_opt_audio") || rule.hasTag("qs_unsupported") }
        Shots.take("50_share_instagram_video_window")
        assertTrue("Video window not shown", rule.hasTag("qs_opt_audio"))
        assertTrue(rule.hasText("Video")) // window title (and option)
        rule.onNodeWithTag("qs_opt_video").assertExists()

        rule.onNodeWithTag("qs_opt_audio").performClick()
        rule.onNodeWithText("Download audio").assertExists()
        rule.onNodeWithTag("qs_download").performScrollTo().performClick()
        rule.waitUntil(20_000) { rule.hasTag("qs_started") || rule.hasTag("qs_saved") }
        Shots.take("51_share_instagram_started")

        scenario.close() // the sheet is gone; the download must carry on
        val id = MediaQueries.waitForNew(MediaQueries.AUDIO, "%instagram%", before, 180_000)
        assertNotNull("audio file never appeared after closing the sheet", id)
        val mimes = MediaQueries.trackMimes(MediaQueries.uri(MediaQueries.AUDIO, id!!))
        assertEquals("audio only", listOf("audio/mp4a-latm"), mimes)
        // Readable name: account + "instagram" + post code + "audio"
        val name = MediaQueries.name(MediaQueries.AUDIO, id)!!
        assertTrue(name, name.startsWith("ndtv_instagram_DeyQf_PzHlD_audio") && name.endsWith(".m4a"))
    }

    @Test fun storyLinkIsExplained() {
        val scenario = shareText("https://www.instagram.com/stories/someone/3141592653589793/")
        rule.waitUntil(10_000) { rule.hasTag("qs_unsupported") }
        rule.onNodeWithText("Can't download this").assertExists()
        Shots.take("52_share_story_unsupported")
        scenario.close()
    }

    /** YouTube shared → resolutions + Audio only; skipped where YouTube blocks the network (CI). */
    @Test fun youtubeLinkShowsResolutionsAndSavesAudio() {
        val before = MediaQueries.ids(MediaQueries.AUDIO)
        val scenario = shareText("https://youtu.be/jNQXAC9IVRw?si=e2e")
        rule.waitUntil(90_000) { rule.hasTag("qs_q_Audio only") || rule.hasTag("qs_unsupported") }
        Shots.take("53_share_youtube")
        assumeFalse("YouTube bot-check on this network", rule.hasText("confirm it's not a bot", substring = true))
        assertTrue(rule.hasTag("qs_q_Audio only"))
        rule.onNodeWithTag("qs_q_Audio only").performScrollTo().performClick()
        rule.onNodeWithTag("qs_download").performScrollTo().performClick()
        rule.waitUntil(20_000) { rule.hasTag("qs_started") || rule.hasTag("qs_saved") }
        scenario.close()
        assertNotNull(MediaQueries.waitForNew(MediaQueries.AUDIO, "%", before, 180_000))
    }

    /** An image file shared from the gallery → Image window → Save. */
    @Test fun sharedImageFileIsSaved() {
        val source = MediaQueries.ids(MediaQueries.IMAGES).firstOrNull()
        assumeTrue("no image in the gallery to share (WhatsApp test saves one)", source != null)
        val before = MediaQueries.ids(MediaQueries.IMAGES)
        val scenario = ActivityScenario.launch<ShareActivity>(
            Intent(ctx, ShareActivity::class.java).setAction(Intent.ACTION_SEND).setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, MediaQueries.uri(MediaQueries.IMAGES, source!!))
        )
        rule.waitUntil(15_000) { rule.hasTag("qs_download") }
        assertTrue(rule.hasText("Image"))
        rule.onNodeWithText("Save image").assertExists()
        Shots.take("54_share_image_window")
        rule.onNodeWithTag("qs_download").performScrollTo().performClick()
        rule.waitUntil(30_000) { rule.hasTag("qs_saved") }
        Shots.take("55_share_image_saved")
        assertNotNull(MediaQueries.waitForNew(MediaQueries.IMAGES, "%", before, 10_000))
        scenario.close()
    }
}
