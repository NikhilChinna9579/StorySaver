package com.storysaver.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A download started in the app must finish even if the app's screen is closed right away. */
@RunWith(AndroidJUnit4::class)
class BackgroundDownloadE2ETest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun instagramDownloadFinishesAfterAppIsClosed() {
        val before = MediaQueries.ids(MediaQueries.VIDEOS, "%instagram%")
        rule.openSource("Video", "Instagram Story")
        rule.waitUntil(10_000) { rule.hasTag("ig_url") }
        rule.onNodeWithTag("ig_url").performTextInput("https://www.instagram.com/reel/DeyQf_PzHlD/?exln=bg")
        rule.onNodeWithTag("ig_find").performClick()
        rule.waitUntil(60_000) { rule.hasTag("ig_save_0") || rule.hasText("Couldn't get this post") }
        assertTrue("reel not resolved", rule.hasTag("ig_save_0"))

        rule.onNodeWithTag("ig_save_0").performScrollTo().performClick()
        Shots.take("60_background_started")
        rule.runOnUiThread { rule.activity.finish() } // leave the app immediately

        val id = MediaQueries.waitForNew(MediaQueries.VIDEOS, "%instagram%", before, 180_000)
        assertNotNull("video never appeared after the app was closed", id)
        val mimes = MediaQueries.trackMimes(MediaQueries.uri(MediaQueries.VIDEOS, id!!))
        assertTrue("tracks=$mimes", mimes.any { it.startsWith("video/") })
    }
}
