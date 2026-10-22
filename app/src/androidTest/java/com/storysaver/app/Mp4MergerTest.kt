package com.storysaver.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.storysaver.app.youtube.Mp4Merger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Merges YouTube-style split streams on the device. CI pushes, into the app's external files dir:
 *   yt-video.mp4 — H.264 video-only, fragmented MP4 (like YouTube DASH), 6 s
 *   yt-audio.m4a — AAC audio-only, fragmented MP4, 6 s
 */
@RunWith(AndroidJUnit4::class)
class Mp4MergerTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.getExternalFilesDir(null), "fixtures")

    @Test fun mergesSplitVideoAndAudioIntoOnePlayableMp4(): Unit = runBlocking {
        val video = File(dir, "yt-video.mp4")
        val audio = File(dir, "yt-audio.m4a")
        assumeTrue("fixtures not pushed", video.exists() && audio.exists())
        val out = File(ctx.cacheDir, "merged-test.mp4")
        var lastProgress = 0f
        Mp4Merger.merge(video, audio, out, durationUs = 6_000_000) { p -> if (p != null) lastProgress = p }

        assertTrue(out.length() > 10_000)
        assertEquals(1f, lastProgress)
        val ex = MediaExtractor()
        ex.setDataSource(out.path)
        val mimes = (0 until ex.trackCount).map { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        ex.release()
        assertTrue("tracks=$mimes", mimes.any { it == "video/avc" } && mimes.any { it == "audio/mp4a-latm" })

        val mmr = MediaMetadataRetriever()
        mmr.setDataSource(out.path)
        val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        val hasAudio = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
        val hasVideo = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
        val frame = mmr.getFrameAtTime(3_000_000)
        mmr.release()
        assertTrue("duration=$durationMs", durationMs in 5_500..6_600)
        assertEquals("yes", hasAudio)
        assertEquals("yes", hasVideo)
        assertTrue("could not decode a frame from the merged file", frame != null)
        out.delete()
    }

    @Test fun corruptInputFailsCleanly(): Unit = runBlocking {
        val bad = File(ctx.cacheDir, "not-a-video.mp4").apply { writeText("definitely not mp4") }
        val out = File(ctx.cacheDir, "merged-bad.mp4")
        try {
            Mp4Merger.merge(bad, bad, out, 1_000_000) {}
            throw AssertionError("merge of garbage should fail")
        } catch (e: java.io.IOException) {
            // expected: a readable error, not a crash
        }
        bad.delete()
        out.delete()
    }
}
