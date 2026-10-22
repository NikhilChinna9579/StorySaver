package com.storysaver.app

import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real network: the user's public reel link, found, saved, and verified in the gallery. */
@RunWith(AndroidJUnit4::class)
class InstagramE2ETest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val link = "https://www.instagram.com/reel/DeyQf_PzHlD/?exln=dmcxMDJ0YmxzMnFn"

    @Test fun publicReelLinkIsFoundAndSaved() {
        rule.openSource("Video", "Instagram Story")
        rule.waitUntil(10_000) { rule.hasTag("ig_url") }
        rule.onNodeWithTag("ig_url").performTextInput(link)
        rule.onNodeWithTag("ig_find").performClick()

        rule.waitUntil(60_000) {
            rule.hasTag("ig_save_0") || rule.hasText("Couldn't get this post") || rule.hasText("Can't download this link")
        }
        Shots.take("10_instagram_found")
        if (!rule.hasTag("ig_save_0")) {
            val shown = rule.onAllNodesWithText("", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
                .flatMap { n -> n.config.toString().lines() }.joinToString(" | ").take(1500)
            fail("Reel was not resolved. Screen: $shown")
        }
        rule.onNodeWithText("1 video", substring = true).assertExists()

        rule.onNodeWithTag("ig_save_0").performScrollTo().performClick()
        rule.waitUntil(120_000) { rule.hasTag("ig_saved_0") || rule.hasText("Couldn't save") }
        Shots.take("11_instagram_saved")
        assertTrue("Save did not complete", rule.hasTag("ig_saved_0"))

        // The file must really be in the gallery (Movies/StorySaver).
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        val found = resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("StorySaver_IG_%"), null,
        )?.use { c ->
            var ok = false
            while (c.moveToNext()) if (c.getLong(1) > 10_000) ok = true
            ok
        } ?: false
        assertTrue("Saved reel not found in MediaStore", found)

        rule.onNodeWithText("Open").assertExists()
    }
}
