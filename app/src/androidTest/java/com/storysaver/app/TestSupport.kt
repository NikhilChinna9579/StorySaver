package com.storysaver.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    // The home screen scrolls (quick link box on top), so bring each control into view first.
    onNodeWithText(type).performScrollTo().performClick()
    onNodeWithText(source).performScrollTo().performClick()
    onNodeWithText("Continue").performScrollTo().performClick()
}

/** The text shown by the node with [tag]. */
fun SemanticsNodeInteractionsProvider.textOf(tag: String): String =
    onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
        ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

/** Waits until the trim screen has loaded the media (its end time is known). */
fun ComposeTestRule.waitForTrimScreen(timeoutMs: Long = 60_000) {
    waitUntil(timeoutMs) {
        hasTag("trim_error") || (hasTag("trim_end") && textOf("trim_end").let { it.isNotEmpty() && it != "0:00.0" })
    }
    org.junit.Assert.assertFalse("trim preview failed to load: ${textOf("trim_error")}", hasTag("trim_error"))
}

/** Taps the node with [tag] [times] times. */
fun ComposeTestRule.tap(tag: String, times: Int = 1) = repeat(times) {
    onNodeWithTag(tag).performClick()
    waitForIdle()
}
