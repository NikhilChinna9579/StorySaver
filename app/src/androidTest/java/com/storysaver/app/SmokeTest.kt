package com.storysaver.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun homeTabsAndSettingsRender() {
        rule.waitUntil(15_000) { rule.hasText("Story Saver") }
        rule.onNodeWithText("Save accessible stories and status media.").assertExists()
        rule.onNodeWithText("Choose a type and a source").assertExists()
        Shots.take("01_home")

        rule.onNodeWithText("Downloads").performClick()
        rule.waitUntil(5_000) { rule.hasText("Downloads") }
        Shots.take("02_downloads")

        rule.onNodeWithText("Settings").performClick()
        rule.waitUntil(5_000) { rule.hasText("Default download destination") }
        Shots.take("03_settings")

        rule.onNodeWithText("Home").performClick()
        rule.waitUntil(5_000) { rule.hasText("Story Saver") }
    }

    @Test fun instagramRejectsBadLinksWithClearMessages() {
        rule.openSource("Image", "Instagram Story")
        rule.waitUntil(10_000) { rule.hasTag("ig_url") }

        rule.onNodeWithTag("ig_url").performTextInput("hello there")
        rule.onNodeWithTag("ig_find").performClick()
        rule.waitUntil(5_000) { rule.hasText("Couldn't get this post") }

        rule.onNodeWithTag("ig_url").performTextReplacement("https://example.com/photo.jpg")
        rule.onNodeWithTag("ig_find").performClick()
        rule.waitUntil(5_000) { rule.hasText("This isn't an Instagram link", substring = true) }

        rule.onNodeWithTag("ig_url").performTextReplacement("https://www.instagram.com/stories/someone/3141592653589793/")
        rule.onNodeWithTag("ig_find").performClick()
        rule.waitUntil(5_000) { rule.hasText("Stories can't be downloaded from a link") }
        Shots.take("04_instagram_story_link")

        rule.onNodeWithTag("ig_url").performTextReplacement("https://www.instagram.com/someone/")
        rule.onNodeWithTag("ig_find").performClick()
        rule.waitUntil(5_000) { rule.hasText("That's not a post or reel link") }
        assertTrue(true)
    }
}
