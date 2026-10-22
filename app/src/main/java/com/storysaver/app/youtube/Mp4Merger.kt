package com.storysaver.app.youtube

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
 * Combines a video-only MP4 (H.264) and an audio-only M4A (AAC) into one MP4 without re-encoding,
 * using Android's built-in MediaExtractor/MediaMuxer. Fast and lossless.
 */
object Mp4Merger {

    suspend fun merge(video: File, audio: File, out: File, durationUs: Long, onProgress: (Float?) -> Unit) {
        val videoEx = MediaExtractor()
        val audioEx = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            videoEx.setDataSource(video.path)
            audioEx.setDataSource(audio.path)
            val vIn = trackOf(videoEx, "video/") ?: throw IOException("The downloaded video has no video track")
            val aIn = trackOf(audioEx, "audio/") ?: throw IOException("The downloaded audio has no audio track")
            videoEx.selectTrack(vIn)
            audioEx.selectTrack(aIn)
            val vFormat = videoEx.getTrackFormat(vIn)
            val aFormat = audioEx.getTrackFormat(aIn)

            out.delete()
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val vOut = m.addTrack(vFormat)
            val aOut = m.addTrack(aFormat)
            m.start()
            started = true

            val size = maxOf(maxInput(vFormat), maxInput(aFormat), 4 * 1024 * 1024)
            val buffer = ByteBuffer.allocate(size)
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false
            var samples = 0L
            while (!videoDone || !audioDone) {
                currentCoroutineContext().ensureActive()
                // Interleave by timestamp so players can stream the result smoothly.
                val useVideo = !videoDone && (audioDone || videoEx.sampleTime <= audioEx.sampleTime)
                val ex = if (useVideo) videoEx else audioEx
                buffer.clear()
                val n = ex.readSampleData(buffer, 0)
                if (n < 0) {
                    if (useVideo) videoDone = true else audioDone = true
                    continue
                }
                info.offset = 0
                info.size = n
                info.presentationTimeUs = ex.sampleTime
                info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                m.writeSampleData(if (useVideo) vOut else aOut, buffer, info)
                ex.advance()
                if (++samples % 200 == 0L && durationUs > 0) {
                    onProgress((info.presentationTimeUs.toDouble() / durationUs).coerceIn(0.0, 1.0).toFloat())
                }
            }
            m.stop() // finalizes the file; failures here must surface, not be swallowed
            started = false
            onProgress(1f)
        } catch (e: IllegalStateException) {
            throw IOException("Couldn't merge video and audio: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Couldn't merge video and audio: ${e.message}", e)
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            videoEx.release()
            audioEx.release()
        }
        if (!out.exists() || out.length() == 0L) throw IOException("Merging produced an empty file")
    }

    private fun trackOf(ex: MediaExtractor, prefix: String): Int? =
        (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true }

    private fun maxInput(f: MediaFormat): Int =
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
}
