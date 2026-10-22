package com.storysaver.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

/**
 * CI pushes a fake WhatsApp folder before this runs:
 *   WhatsApp/Media/.Statuses: 2 .jpg, 2 .mp4, 1 extension-less mp4, .nomedia, 1 empty .mp4
 *   WhatsApp/accounts/999/Media/.Statuses: 1 .jpg, 1 .mp4
 * Expected: 3 images, 4 videos, 1 skipped (the empty file).
 */
@RunWith(AndroidJUnit4::class)
class WhatsAppE2ETest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun grantsFolderAndListsEveryStatus() {
        rule.openSource("Video", "WhatsApp Status")
        rule.waitUntil(10_000) { rule.hasText("Grant folder access") || rule.hasTag("wa_counts") }
        Shots.take("20_whatsapp_start")

        if (rule.hasText("Grant folder access")) {
            rule.onNodeWithText("Grant folder access").performClick()
            // System folder picker (DocumentsUI) opens in the WhatsApp folder.
            val use = device.wait(Until.findObject(By.text(Pattern.compile("(?i)use this folder"))), 20_000)
            Shots.take("21_folder_picker")
            assertTrue("Folder picker did not show 'Use this folder'", use != null)
            use.click()
            val allow = device.wait(Until.findObject(By.text(Pattern.compile("(?i)^allow$"))), 10_000)
            Shots.take("22_allow_dialog")
            allow?.click()
        }

        rule.waitUntil(30_000) { rule.hasTag("wa_counts") }
        Shots.take("23_whatsapp_videos")
        rule.onNodeWithTag("wa_counts", useUnmergedTree = true)
            .assertTextContainsCompat("Found 3 images · 4 videos · 1 skipped")
        assertTrue(rule.onAllNodesWithTag("wa_tile").fetchSemanticsNodes().size == 4)

        // Images tab shows the 3 images.
        rule.onNodeWithText("Image").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("wa_tile").fetchSemanticsNodes().size == 3 }
        Shots.take("24_whatsapp_images")

        // Save one and confirm.
        rule.onAllNodesWithTag("wa_tile").onFirst().performClick()
        rule.waitUntil(10_000) { rule.hasText("Save image") }
        rule.onNodeWithText("Save image").performClick()
        rule.waitUntil(30_000) { rule.hasText("Saved") || rule.hasText("Something went wrong") }
        Shots.take("25_whatsapp_saved")
        assertTrue("WhatsApp status was not saved", rule.hasText("Saved"))

        // Details dialog lists both accounts' folders.
        rule.onNodeWithText("Save another").performClick()
        rule.onNodeWithText("Details").performClick()
        rule.waitUntil(5_000) { rule.hasText("What was scanned") }
        assertTrue(rule.hasText("accounts/999", substring = true))
        Shots.take("26_whatsapp_details")
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextContainsCompat(text: String) {
    val node = fetchSemanticsNode()
    val all = node.config.toString()
    assertTrue("Expected '$text' in: $all", all.contains(text))
}
