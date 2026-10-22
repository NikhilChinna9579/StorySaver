package com.storysaver.app.media

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Cuts a part out of an MP4 video or M4A audio file without re-encoding (lossless and fast),
 * using Android's MediaExtractor/MediaMuxer.
 *
 * Without re-encoding a video can only start on a keyframe, so a video clip starts at the last
 * keyframe at or before the chosen start (usually less than a second or two earlier). The sound is
 * cut at the same point so it stays in sync. Audio-only clips start exactly where chosen.
 */
object MediaTrimmer {

    data class Result(val hasVideo: Boolean, val actualStartMs: Long, val lengthMs: Long) {
        val mime: String get() = if (hasVideo) "video/mp4" else "audio/mp4"
    }

    suspend fun trim(input: File, out: File, range: TrimRange, audioOnly: Boolean, onProgress: (Float?) -> Unit): Result {
        val startUs = range.startMs * 1000
        val endUs = range.endMs * 1000
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var started = false
        fun open(): MediaExtractor = MediaExtractor().also {
            extractors += it
            it.setDataSource(input.path)
        }
        try {
            val probe = open()
            var videoTrack = -1
            var audioTrack = -1
            for (i in 0 until probe.trackCount) {
                val mime = probe.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoTrack < 0) videoTrack = i
                if (mime.startsWith("audio/") && audioTrack < 0) audioTrack = i
            }
            if (audioOnly) {
                if (audioTrack < 0) throw IOException("This video has no sound track")
                videoTrack = -1
            }
            if (videoTrack < 0 && audioTrack < 0) throw IOException("There's no video or sound in this file to trim")
            if (videoTrack < 0) {
                val mime = probe.getTrackFormat(audioTrack).getString(MediaFormat.KEY_MIME)
                if (mime != MediaFormat.MIMETYPE_AUDIO_AAC) {
                    throw IOException("The sound in this file isn't AAC ($mime), so it can't be trimmed into M4A")
                }
            }

            // Where the clip really starts: the keyframe at/before the chosen start (video), else the start.
            val baseUs = if (videoTrack >= 0) keyframeAtOrBefore(probe, ::open, videoTrack, startUs) else startUs

            val tracks = listOf(videoTrack, audioTrack).filter { it >= 0 }
            out.delete()
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            if (videoTrack >= 0) {
                val vf = probe.getTrackFormat(videoTrack)
                if (vf.containsKey(MediaFormat.KEY_ROTATION)) m.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION))
            }
            class Src(val ex: MediaExtractor, val outTrack: Int, val isVideo: Boolean) {
                var done = false
            }
            val sources = tracks.map { track ->
                val ex = open()
                ex.selectTrack(track)
                val format = ex.getTrackFormat(track)
                val isVideo = track == videoTrack
                positionAt(ex, ::open, track, baseUs, isVideo).let { positioned ->
                    Src(positioned, m.addTrack(format), isVideo)
                }
            }
            m.start()
            started = true

            val size = maxOf(tracks.maxOf { maxInput(probe.getTrackFormat(it)) }, 4 * 1024 * 1024)
            val buffer = ByteBuffer.allocate(size)
            val info = MediaCodec.BufferInfo()
            val spanUs = (endUs - baseUs).coerceAtLeast(1)
            var samples = 0L
            var lastPts = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val src = sources.filter { !it.done }.minByOrNull { it.ex.sampleTime } ?: break
                val t = src.ex.sampleTime
                if (t < 0 || t >= endUs) { src.done = true; continue }
                val pts = t - baseUs
                if (pts < 0) { src.ex.advance(); continue } // leading B-frames / audio before the cut
                buffer.clear()
                val n = src.ex.readSampleData(buffer, 0)
                if (n < 0) { src.done = true; continue }
                info.offset = 0
                info.size = n
                info.presentationTimeUs = pts
                info.flags = if (src.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                m.writeSampleData(src.outTrack, buffer, info)
                if (pts > lastPts) lastPts = pts
                src.ex.advance()
                if (++samples % 100 == 0L) onProgress((pts.toDouble() / spanUs).coerceIn(0.0, 1.0).toFloat())
            }
            if (samples == 0L) throw IOException("The chosen part is past the end of the file")
            m.stop()
            started = false
            onProgress(1f)
            if (!out.exists() || out.length() == 0L) throw IOException("Trimming produced an empty file")
            return Result(hasVideo = videoTrack >= 0, actualStartMs = baseUs / 1000, lengthMs = lastPts / 1000)
        } catch (e: IllegalStateException) {
            throw IOException("Couldn't trim this file: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Couldn't trim this file: ${e.message}", e)
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            extractors.forEach { runCatching { it.release() } }
        }
    }

    /** Time of the last keyframe at or before [startUs] on [track]. */
    private suspend fun keyframeAtOrBefore(probe: MediaExtractor, open: () -> MediaExtractor, track: Int, startUs: Long): Long {
        var ex = probe
        ex.selectTrack(track)
        ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (ex.sampleTime !in 0..startUs) {
            // Seeking isn't reliable in every file (e.g. some fragmented MP4s): scan from the start instead.
            ex = open().also { it.selectTrack(track) }
        }
        var lastSync = -1L
        var first = -1L
        while (true) {
            currentCoroutineContext().ensureActive()
            val t = ex.sampleTime
            if (t < 0) break
            if (first < 0) first = t
            val sync = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            if (sync && t <= startUs) lastSync = t
            if (sync && t > startUs) break
            ex.advance()
        }
        return when {
            lastSync >= 0 -> lastSync
            first >= 0 -> first
            else -> 0
        }
    }

    /** Positions [ex] on the first sample to write: the keyframe at [baseUs] (video) or the first sample ≥ it. */
    private suspend fun positionAt(ex0: MediaExtractor, open: () -> MediaExtractor, track: Int, baseUs: Long, isVideo: Boolean): MediaExtractor {
        var ex = ex0
        ex.seekTo(baseUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        if (ex.sampleTime !in 0..baseUs) ex = open().also { it.selectTrack(track) }
        while (true) {
            currentCoroutineContext().ensureActive()
            val t = ex.sampleTime
            if (t < 0) break
            val sync = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            if (isVideo && sync && t >= baseUs) break
            if (!isVideo && t >= baseUs) break
            ex.advance()
        }
        return ex
    }

    private fun maxInput(f: MediaFormat): Int =
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
}
