package com.storysaver.app

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File

/** Full-screen screenshots (including system UI such as the folder picker), pulled by CI. */
object Shots {
    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun take(name: String) {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        runCatching { device.takeScreenshot(File(dir, "$name.png")) }
    }
}

/** Slow CI emulators sometimes show "<app> isn't responding" over everything; tap Wait. */
fun dismissSystemDialogs() {
    val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    repeat(3) {
        val wait = device.findObject(androidx.test.uiautomator.By.text("Wait")) ?: return
        wait.click()
        device.waitForIdle(1_000)
    }
}

fun SemanticsNodeInteractionsProvider.hasTag(tag: String) =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

fun SemanticsNodeInteractionsProvider.hasText(text: String, substring: Boolean = false) =
    onAllNodesWithText(text, substring = substring, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

/** Home → pick a media type and a source → Continue. */
fun ComposeTestRule.openSource(type: String, source: String) {
    waitUntil(15_000) { hasText("Story Saver") }
    onNodeWithText(type).performClick()
    onNodeWithText(source).performClick()
    onNodeWithText("Continue").performClick()
}
