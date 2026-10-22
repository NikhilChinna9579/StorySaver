package com.storysaver.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.storysaver.app.media.MediaTrimmer
import com.storysaver.app.media.TrimRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * Lossless trimming on a real device, with fixtures made by CI (ffmpeg):
 *   trim-src.mp4 — 10 s, H.264 640x360 30 fps with a keyframe every second, AAC sound
 *   trim-rot.mp4 — same, marked as rotated 90° (portrait phone video)
 *   trim-src.m4a — 10 s AAC
 *   yt-video.mp4 — 6 s fragmented H.264, a single keyframe at 0 (YouTube-style)
 */
@RunWith(AndroidJUnit4::class)
class MediaTrimmerTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.getExternalFilesDir(null), "fixtures")

    private fun fixture(name: String): File = File(dir, name).also { assumeTrue("fixture $name not pushed", it.exists()) }
    private fun out(name: String) = File(ctx.cacheDir, name).also { it.delete() }

    private fun meta(f: File): Map<String, String?> {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(f.path)
            mapOf(
                "duration" to mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION),
                "video" to mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO),
                "audio" to mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO),
                "rotation" to mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                "frame" to (if (runCatching { mmr.getFrameAtTime(0) }.getOrNull() != null) "yes" else null),
            )
        } finally {
            mmr.release()
        }
    }

    private fun durationMs(f: File) = meta(f)["duration"]!!.toLong()

    private fun tracks(f: File): List<String> {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(f.path)
            (0 until ex.trackCount).mapNotNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        } finally {
            ex.release()
        }
    }

    @Test fun cutsVideoAtAKeyframe(): Unit = runBlocking {
        val o = out("t1.mp4")
        val r = MediaTrimmer.trim(fixture("trim-src.mp4"), o, TrimRange(3_000, 7_000), audioOnly = false) {}
        assertTrue(r.hasVideo)
        assertEquals(3_000, r.actualStartMs)
        val m = meta(o)
        assertTrue("duration=${m["duration"]}", durationMs(o) in 3_900..4_200)
        assertEquals("yes", m["video"]); assertEquals("yes", m["audio"]); assertNotNull("first frame must decode", m["frame"])
    }

    @Test fun startBetweenKeyframesMovesBackToTheKeyframe(): Unit = runBlocking {
        val o = out("t2.mp4")
        val r = MediaTrimmer.trim(fixture("trim-src.mp4"), o, TrimRange(3_500, 8_000), audioOnly = false) {}
        assertEquals(3_000, r.actualStartMs)
        assertTrue("duration=${durationMs(o)}", durationMs(o) in 4_900..5_200)
        assertNotNull(meta(o)["frame"])
    }

    @Test fun soundOnlyFromAVideo(): Unit = runBlocking {
        val o = out("t3.m4a")
        val r = MediaTrimmer.trim(fixture("trim-src.mp4"), o, TrimRange(2_000, 5_000), audioOnly = true) {}
        assertEquals("audio/mp4", r.mime)
        assertEquals(listOf("audio/mp4a-latm"), tracks(o))
        assertTrue("duration=${durationMs(o)}", durationMs(o) in 2_900..3_100)
    }

    @Test fun audioFileIsCutPrecisely(): Unit = runBlocking {
        val o = out("t4.m4a")
        val r = MediaTrimmer.trim(fixture("trim-src.m4a"), o, TrimRange(1_000, 4_000), audioOnly = false) {}
        assertTrue(!r.hasVideo)
        assertTrue("start=${r.actualStartMs}", r.actualStartMs in 1_000..1_050)
        assertTrue("duration=${durationMs(o)}", durationMs(o) in 2_900..3_100)
    }

    @Test fun keepsPortraitRotation(): Unit = runBlocking {
        val src = fixture("trim-rot.mp4")
        val rotation = meta(src)["rotation"]
        assumeTrue("fixture isn't rotated ($rotation)", rotation != null && rotation != "0")
        val o = out("t5.mp4")
        MediaTrimmer.trim(src, o, TrimRange(2_000, 4_000), audioOnly = false) {}
        assertEquals(rotation, meta(o)["rotation"])
    }

    @Test fun fragmentedYouTubeStyleFile(): Unit = runBlocking {
        val o = out("t6.mp4")
        val r = MediaTrimmer.trim(fixture("yt-video.mp4"), o, TrimRange(2_000, 4_000), audioOnly = false) {}
        assertTrue("start=${r.actualStartMs}", r.actualStartMs in 0..2_000) // keyframe at/before 2 s (normally 0)
        val expected = 4_000 - r.actualStartMs
        assertTrue("duration=${durationMs(o)} expected≈$expected", durationMs(o) in expected - 200..expected + 200)
        assertNotNull(meta(o)["frame"])
    }

    @Test fun rangePastTheEndFailsCleanly(): Unit = runBlocking {
        try {
            MediaTrimmer.trim(fixture("trim-src.m4a"), out("t7.m4a"), TrimRange(12_000, 15_000), audioOnly = false) {}
            throw AssertionError("expected a failure")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test fun garbageFailsCleanly(): Unit = runBlocking {
        val bad = File(ctx.cacheDir, "garbage.mp4").apply { writeText("not a video") }
        try {
            MediaTrimmer.trim(bad, out("t8.mp4"), TrimRange(0, 2_000), audioOnly = false) {}
            throw AssertionError("expected a failure")
        } catch (e: IOException) {
            // expected: a readable error, not a crash
        }
    }
}
