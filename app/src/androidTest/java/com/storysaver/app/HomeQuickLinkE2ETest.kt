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

/** The "Have a link?" box on the home screen detects the link and opens the download window. */
@RunWith(AndroidJUnit4::class)
class HomeQuickLinkE2ETest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun pastedInstagramLinkOpensDownloadWindow() {
        rule.waitUntil(15_000) { rule.hasTag("quick_link") }
        Shots.take("70_home_quick_box")
        rule.onNodeWithTag("quick_link").performTextInput("https://www.instagram.com/reel/DeyQf_PzHlD/?igsh=home")
        rule.onNodeWithText("Instagram").assertExists() // button shows what was detected
        rule.onNodeWithTag("quick_go").performClick()
        rule.waitUntil(60_000) { rule.hasTag("qs_opt_audio") || rule.hasTag("qs_unsupported") }
        Shots.take("71_home_quick_sheet")
        assertTrue("download window didn't open with options", rule.hasTag("qs_opt_audio"))
    }

    @Test fun unsupportedLinkIsExplainedInline() {
        rule.waitUntil(15_000) { rule.hasTag("quick_link") }
        rule.onNodeWithTag("quick_link").performTextInput("https://www.instagram.com/stories/someone/1/")
        rule.onNodeWithTag("quick_go").performClick()
        rule.waitUntil(5_000) { rule.hasTag("quick_message") }
        rule.onNodeWithTag("quick_link").performTextReplacement("hello")
        rule.waitUntil(5_000) { !rule.hasTag("quick_message") } // typing clears the message
    }

    @Test fun stepByStepFlowStillWorks() {
        rule.openSource("Image", "Instagram Story")
        rule.waitUntil(10_000) { rule.hasTag("ig_url") }
    }
}
