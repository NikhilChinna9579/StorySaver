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
 * Lossless MP4 remuxing with Android's built-in MediaExtractor/MediaMuxer (no re-encoding):
 *  - [merge]: video-only MP4 (H.264) + audio-only M4A (AAC) → one MP4 (YouTube above 360p);
 *  - [extractAudio]: keep only the AAC sound of an MP4 → .m4a ("Audio only").
 */
object Mp4Merger {

    suspend fun merge(video: File, audio: File, out: File, durationUs: Long, onProgress: (Float?) -> Unit) =
        remux(listOf(video to "video/", audio to "audio/"), out, durationUs, onProgress)

    suspend fun extractAudio(input: File, out: File, durationUs: Long, onProgress: (Float?) -> Unit) =
        remux(listOf(input to "audio/"), out, durationUs, onProgress)

    private class Source(val extractor: MediaExtractor, val outTrack: Int) {
        var done = false
    }

    private suspend fun remux(inputs: List<Pair<File, String>>, out: File, durationUs: Long, onProgress: (Float?) -> Unit) {
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            val picked = inputs.map { (file, prefix) ->
                val ex = MediaExtractor().also { extractors += it }
                ex.setDataSource(file.path)
                val track = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
                } ?: throw IOException(if (prefix == "audio/") "This video has no sound track" else "The downloaded file has no video track")
                val mime = ex.getTrackFormat(track).getString(MediaFormat.KEY_MIME)
                if (prefix == "audio/" && mime != MediaFormat.MIMETYPE_AUDIO_AAC) {
                    throw IOException("The sound in this video isn't AAC ($mime), so it can't be saved as M4A")
                }
                ex.selectTrack(track)
                ex to ex.getTrackFormat(track)
            }

            out.delete()
            val m = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val sources = picked.map { (ex, format) -> Source(ex, m.addTrack(format)) }
            m.start()
            started = true

            val size = maxOf(picked.maxOf { maxInput(it.second) }, 4 * 1024 * 1024)
            val buffer = ByteBuffer.allocate(size)
            val info = MediaCodec.BufferInfo()
            var samples = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                // Interleave by timestamp so players can stream the result smoothly.
                val src = sources.filter { !it.done }.minByOrNull { it.extractor.sampleTime } ?: break
                buffer.clear()
                val n = src.extractor.readSampleData(buffer, 0)
                if (n < 0) { src.done = true; continue }
                info.offset = 0
                info.size = n
                info.presentationTimeUs = src.extractor.sampleTime
                info.flags = if (src.extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                m.writeSampleData(src.outTrack, buffer, info)
                src.extractor.advance()
                if (++samples % 200 == 0L && durationUs > 0) {
                    onProgress((info.presentationTimeUs.toDouble() / durationUs).coerceIn(0.0, 1.0).toFloat())
                }
            }
            if (samples == 0L) throw IOException("There was nothing to save in the downloaded file")
            m.stop() // finalizes the file; failures here must surface, not be swallowed
            started = false
            onProgress(1f)
        } catch (e: IllegalStateException) {
            throw IOException("Couldn't process the media: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("Couldn't process the media: ${e.message}", e)
        } finally {
            runCatching { if (started) muxer?.stop() }
            runCatching { muxer?.release() }
            extractors.forEach { runCatching { it.release() } }
        }
        if (!out.exists() || out.length() == 0L) throw IOException("Processing produced an empty file")
    }

    private fun maxInput(f: MediaFormat): Int =
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
}
