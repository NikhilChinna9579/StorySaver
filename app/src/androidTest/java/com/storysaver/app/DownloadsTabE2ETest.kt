package com.storysaver.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs after the other tests, so the Downloads tab has Instagram videos, audio and images in it. */
@RunWith(AndroidJUnit4::class)
class DownloadsTabE2ETest {

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun summary(): String = rule.onNodeWithTag("dl_summary", useUnmergedTree = true)
        .fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    private fun count(): Int = summary().substringBefore(' ').toInt()

    private fun openTab() {
        rule.waitUntil(15_000) { rule.hasText("Downloads") }
        rule.onNodeWithText("Downloads").performClick()
        rule.waitUntil(10_000) { rule.hasTag("dl_summary") }
    }

    @Test fun filtersSearchSelectionAndViewer() {
        openTab()
        val total = count()
        assumeTrue("needs earlier tests' downloads", total >= 3)
        Shots.take("80_downloads_all")

        // Source filter
        rule.onNodeWithTag("dl_src_INSTAGRAM").performClick()
        rule.waitUntil(5_000) { count() in 1 until total + 1 }
        val instagram = count()
        assertTrue(summary().contains("(of $total)"))
        Shots.take("81_downloads_instagram")

        // Type filter on top: only audio
        rule.onNodeWithTag("dl_type_AUDIO").performClick()
        rule.waitUntil(5_000) { count() < instagram || count() == 0 }
        Shots.take("82_downloads_audio")
        rule.onNodeWithTag("dl_type_ALL").performClick()
        rule.onNodeWithTag("dl_src_ALL").performClick()
        rule.waitUntil(5_000) { count() == total }

        // Search: readable names make this useful
        rule.onNodeWithTag("dl_search").performTextInput("ndtv")
        rule.waitUntil(5_000) { count() in 1 until total + 1 }
        rule.onNodeWithTag("dl_search").performTextReplacement("zzzz-no-match")
        rule.waitUntil(5_000) { rule.hasText("Nothing matches") }
        rule.onNodeWithText("Clear search and filters").performClick()
        rule.waitUntil(5_000) { count() == total }

        // Multi-select: long-press, select all, cancel
        rule.onAllNodesWithTag("dl_row").onFirst().performTouchInput { longClick() }
        rule.waitUntil(5_000) { rule.hasTag("dl_selected_count") }
        rule.onNodeWithText("1 selected").assertExists()
        rule.onNodeWithTag("dl_share_selected").assertExists()
        Shots.take("83_downloads_selected")
        rule.onNode(SemanticsMatcher("cancel selection") {
            it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains("Cancel selection") == true
        }).performClick()
        rule.waitUntil(5_000) { !rule.hasTag("dl_selected_count") }

        // In-app viewer: a video plays in the app, an image opens in the image viewer
        rule.onNodeWithTag("dl_type_VIDEO").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("dl_row").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("dl_row").onFirst().performClick()
        rule.waitUntil(10_000) { rule.hasTag("viewer_player") }
        Shots.take("84_viewer_video")
        Espresso.pressBack()
        rule.waitUntil(10_000) { rule.hasTag("dl_summary") }

        rule.onNodeWithTag("dl_type_IMAGE").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("dl_row").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("dl_row").onFirst().performClick()
        rule.waitUntil(10_000) { rule.hasTag("viewer_image") }
        Shots.take("85_viewer_image")
        Espresso.pressBack()
        rule.waitUntil(10_000) { rule.hasTag("dl_summary") }
        rule.onNodeWithTag("dl_type_ALL").performClick()
    }

    @Test fun deleteRemovesTheFile() {
        openTab()
        val before = MediaQueries.ids(MediaQueries.IMAGES)
        assumeTrue("needs an image to delete", before.isNotEmpty())
        val total = count()
        rule.onNodeWithTag("dl_type_IMAGE").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("dl_row").fetchSemanticsNodes().isNotEmpty() }
        // Select one image and delete it through the selection bar
        rule.onAllNodesWithTag("dl_row").onFirst().performTouchInput { longClick() }
        rule.waitUntil(5_000) { rule.hasTag("dl_delete_selected") }
        rule.onNodeWithTag("dl_delete_selected").performClick()
        rule.waitUntil(5_000) { rule.hasTag("dl_confirm_delete") }
        rule.onNodeWithTag("dl_confirm_delete").performClick()
        rule.onNodeWithTag("dl_type_ALL").performClick()
        rule.waitUntil(15_000) { count() == total - 1 }
        val after = MediaQueries.ids(MediaQueries.IMAGES)
        assertEquals("exactly one image deleted from the phone", before.size - 1, after.size)
    }
}
