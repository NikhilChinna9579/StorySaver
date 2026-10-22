package com.storysaver.app.youtube

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Downloads a large file in HTTP Range chunks. YouTube's media servers slow down or drop long
 * single requests, so chunking keeps big videos reliable. Each chunk is retried a few times.
 * Pure OkHttp (no Android), unit-tested against a mock server.
 */
class ChunkedDownloader(
    private val client: OkHttpClient,
    private val chunkSize: Long = 8L * 1024 * 1024,
    private val retries: Int = 3,
) {

    class HttpError(val code: Int) : IOException("HTTP $code")

    /**
     * Downloads [url] into [dest] (overwritten). [onBytes] gets (bytesSoFar, totalOrMinus1).
     * @return bytes written.
     */
    suspend fun download(
        url: String,
        userAgent: String,
        dest: File,
        knownLength: Long = -1,
        onBytes: (Long, Long) -> Unit = { _, _ -> },
    ): Long = withContext(Dispatchers.IO) { downloadBlocking(url, userAgent, dest, knownLength, onBytes) }

    private suspend fun downloadBlocking(
        url: String, userAgent: String, dest: File, knownLength: Long, onBytes: (Long, Long) -> Unit,
    ): Long {
        dest.parentFile?.mkdirs()
        var total = knownLength
        var done = 0L
        try {
        FileOutputStream(dest).use { out ->
            while (total < 0 || done < total) {
                currentCoroutineContext().ensureActive()
                val end = if (total > 0) minOf(done + chunkSize, total) - 1 else done + chunkSize - 1
                val (got, reportedTotal, wholeFile) = fetchChunk(url, userAgent, done, end, out) { n ->
                    onBytes(done + n, total)
                }
                if (reportedTotal > 0) total = reportedTotal
                done += got
                onBytes(done, total)
                if (wholeFile) break // server ignored Range and sent everything
                if (got == 0L) break // nothing more to read
                if (total < 0 && got < end - (done - got) + 1) break // short read at unknown length = end
            }
        }
        } catch (t: Throwable) {
            dest.delete() // never leave a partial file behind
            throw t
        }
        if (total > 0 && done != total) {
            dest.delete()
            throw IOException("Download incomplete ($done of $total bytes)")
        }
        if (done == 0L) {
            dest.delete()
            throw IOException("The server sent an empty file")
        }
        return done
    }

    private data class Chunk(val bytes: Long, val total: Long, val wholeFile: Boolean)

    private suspend fun fetchChunk(
        url: String, ua: String, start: Long, end: Long, out: FileOutputStream, onPartial: (Long) -> Unit,
    ): Chunk {
        var lastError: IOException? = null
        repeat(retries) { attempt ->
            currentCoroutineContext().ensureActive()
            val channelPos = out.channel.position()
            try {
                return readOnce(url, ua, start, end, out, onPartial)
            } catch (e: HttpError) {
                if (e.code in 400..499 && e.code != 408 && e.code != 429) throw e // won't fix itself
                lastError = e
            } catch (e: IOException) {
                lastError = e
            }
            // Roll back anything written by the failed attempt, then retry.
            out.channel.truncate(channelPos)
            out.channel.position(channelPos)
            if (attempt < retries - 1) kotlinx.coroutines.delay(1000L * (attempt + 1))
        }
        throw lastError ?: IOException("Download failed")
    }

    private suspend fun readOnce(
        url: String, ua: String, start: Long, end: Long, out: FileOutputStream, onPartial: (Long) -> Unit,
    ): Chunk {
        val request = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Range", "bytes=$start-$end")
            .build()
        client.newCall(request).execute().use { resp ->
            if (resp.code != 200 && resp.code != 206) throw HttpError(resp.code)
            val body = resp.body ?: throw IOException("Empty response")
            val whole = resp.code == 200
            if (whole && start > 0) {
                // Server ignored Range on a later chunk: we can't splice it safely.
                throw IOException("Server doesn't support resuming")
            }
            val total = if (whole) body.contentLength() else parseTotal(resp.header("Content-Range"))
            var n = 0L
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val r = input.read(buf)
                    if (r < 0) break
                    out.write(buf, 0, r)
                    n += r
                    onPartial(n)
                }
            }
            val expected = if (whole) body.contentLength() else end - start + 1
            if (!whole && total > 0 && n != minOf(expected, total - start)) {
                throw IOException("Chunk cut short")
            }
            return Chunk(n, total, whole)
        }
    }

    companion object {
        /** "bytes 0-99/1234" → 1234; "*" or missing → -1. */
        fun parseTotal(contentRange: String?): Long =
            contentRange?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1
    }
}
