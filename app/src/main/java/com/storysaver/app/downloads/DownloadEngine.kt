package com.storysaver.app.downloads

import android.net.Uri
import com.storysaver.app.AppContainer
import com.storysaver.app.data.DirectMediaDownloader
import com.storysaver.app.data.MediaSaver
import com.storysaver.app.model.SavedMedia
import com.storysaver.app.util.Progress
import com.storysaver.app.util.formatBytes
import com.storysaver.app.youtube.ChunkedDownloader
import com.storysaver.app.youtube.Mp4Merger
import com.storysaver.app.youtube.YouTubeExtractor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A download that failed with a message meant for the user. */
class DownloadFailure(message: String) : Exception(message)

/**
 * Performs one [DownloadRequest] end to end: download → (merge / extract audio) → save to the
 * gallery → record in history. Used by the background worker. Temp files are always cleaned up.
 */
class DownloadEngine(private val c: AppContainer) {

    /** [progress] gets a step name ("Downloading", "Merging", "Saving") and a 0..1 fraction or null. */
    suspend fun run(request: DownloadRequest, progress: (String, Float?) -> Unit): SavedMedia {
        val dir = File(c.appContext.cacheDir, "jobs").apply { mkdirs() }
        val stamp = "${System.nanoTime()}"
        val temps = mutableListOf<File>()
        fun temp(name: String) = File(dir, "${stamp}_$name").also { temps += it }
        try {
            val (file, mime) = when (request) {
                is DownloadRequest.Direct -> direct(request, ::temp, progress)
                is DownloadRequest.YouTube -> youtube(request, dir, ::temp, progress)
            }
            progress("Saving", null)
            return when (val out = c.saver.save(Uri.fromFile(file), mime, request.platform) { p -> progress("Saving", p) }) {
                is MediaSaver.Outcome.Saved -> out.media.also { c.history.add(it) }
                is MediaSaver.Outcome.Failed -> throw DownloadFailure(out.message)
                MediaSaver.Outcome.NeedsStoragePermission ->
                    throw DownloadFailure("Story Saver needs storage permission. Open the app, tap download again and allow it.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: DownloadFailure) {
            throw e
        } catch (e: ChunkedDownloader.HttpError) {
            throw DownloadFailure(
                if (e.code == 403) "The server refused the download (HTTP 403). The link may have expired — look it up again and retry."
                else "The server returned an error (HTTP ${e.code}). Please try again."
            )
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            throw DownloadFailure(
                if (msg.contains("ENOSPC") || msg.contains("No space", true)) "Your phone ran out of storage during the download."
                else "The download failed: ${msg.ifBlank { "network error" }}"
            )
        } finally {
            temps.forEach { it.delete() }
        }
    }

    private suspend fun direct(
        r: DownloadRequest.Direct, temp: (String) -> File, progress: (String, Float?) -> Unit,
    ): Pair<File, String> {
        progress("Downloading", null)
        val fetched = when (val f = c.downloader.fetch(r.url, null) { p -> progress("Downloading", p) }) {
            is DirectMediaDownloader.Result.Success -> f
            is DirectMediaDownloader.Result.Failure -> throw DownloadFailure(f.message)
        }
        val downloaded = fetched.file // removed in the finally block below
        try {
            if (!r.audioOnly) return copyToTemp(downloaded, temp("media")) to fetched.mimeType
            if (!fetched.mimeType.startsWith("video/")) throw DownloadFailure("Only videos have sound to save.")
            progress("Extracting audio", 0f)
            val out = temp("audio.m4a")
            withContext(Dispatchers.IO) { Mp4Merger.extractAudio(downloaded, out, 0) { p -> progress("Extracting audio", p) } }
            return out to "audio/mp4"
        } finally {
            downloaded.delete()
        }
    }

    private suspend fun youtube(
        r: DownloadRequest.YouTube, dir: File, temp: (String) -> File, progress: (String, Float?) -> Unit,
    ): Pair<File, String> {
        val approx = listOf(r.videoLength, r.audioLength).filter { it > 0 }.sum()
        if (approx > 0 && dir.usableSpace < approx * 2 + 50L * 1024 * 1024) {
            throw DownloadFailure("Not enough free storage. This needs about ${formatBytes(approx * 2)} free while downloading.")
        }
        val dl = c.chunkedDownloader
        val durationUs = r.durationSeconds * 1_000_000

        if (r.audioOnly) {
            val raw = temp("a.m4a")
            dl.download(r.videoUrl, YouTubeExtractor.userAgentFor(r.videoUrl), raw, r.videoLength) { done, total ->
                progress("Downloading audio", Progress.fraction(done, total))
            }
            // YouTube audio is fragmented (DASH); remux into a standard .m4a every player understands.
            val out = temp("audio.m4a")
            progress("Finishing audio", 0f)
            withContext(Dispatchers.IO) { Mp4Merger.extractAudio(raw, out, durationUs) { p -> progress("Finishing audio", p) } }
            return out to "audio/mp4"
        }

        if (r.audioUrl == null) {
            val out = temp("v.mp4")
            dl.download(r.videoUrl, YouTubeExtractor.userAgentFor(r.videoUrl), out, r.videoLength) { done, total ->
                progress("Downloading video", Progress.fraction(done, total))
            }
            return out to "video/mp4"
        }

        val both = if (r.videoLength > 0 && r.audioLength > 0) r.videoLength + r.audioLength else -1L
        val v = temp("v.mp4")
        val a = temp("a.m4a")
        dl.download(r.videoUrl, YouTubeExtractor.userAgentFor(r.videoUrl), v, r.videoLength) { done, total ->
            progress("Downloading video", if (both > 0) Progress.fraction(done, both) else Progress.fraction(done, total))
        }
        dl.download(r.audioUrl, YouTubeExtractor.userAgentFor(r.audioUrl), a, r.audioLength) { done, total ->
            progress("Downloading audio", if (both > 0) Progress.fraction(r.videoLength + done, both) else Progress.fraction(done, total))
        }
        val out = temp("merged.mp4")
        progress("Merging video and audio", 0f)
        withContext(Dispatchers.IO) { Mp4Merger.merge(v, a, out, durationUs) { p -> progress("Merging video and audio", p) } }
        v.delete(); a.delete()
        return out to "video/mp4"
    }

    private suspend fun copyToTemp(src: File, dest: File): File = withContext(Dispatchers.IO) {
        if (!src.renameTo(dest)) src.copyTo(dest, overwrite = true)
        dest
    }
}
