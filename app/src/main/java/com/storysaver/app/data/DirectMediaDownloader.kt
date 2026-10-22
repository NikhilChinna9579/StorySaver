package com.storysaver.app.data

import com.storysaver.app.model.MediaType
import com.storysaver.app.util.MimeUtils
import com.storysaver.app.util.StreamCopier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Downloads a *direct* media file (e.g. an image or video on Instagram's CDN) into the app's
 * cache so it can be previewed, then handed to [MediaSaver].
 *
 * It sends a plain anonymous HTTPS GET. It never logs in, sends cookies, spoofs a browser,
 * or retries around 401/403/429 — if the server refuses, we report that honestly.
 */
class DirectMediaDownloader(
    private val client: OkHttpClient,
    private val cacheDir: File,
) {

    sealed interface Result {
        data class Success(val file: File, val mimeType: String, val sizeBytes: Long) : Result
        data class Failure(val message: String) : Result
    }

    suspend fun fetch(url: String, expected: MediaType, onProgress: (Float?) -> Unit): Result =
        withContext(Dispatchers.IO) { doFetch(url, expected, onProgress) }

    private suspend fun doFetch(url: String, expected: MediaType, onProgress: (Float?) -> Unit): Result {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "StorySaver/1.0 (Android)")
            .build()
        val call = client.newCall(request)
        var tmp: File? = null
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return Result.Failure(httpMessage(response.code))

                val body = response.body ?: return Result.Failure("The server sent an empty response.")
                val mime = MimeUtils.normalize(response.header("Content-Type"))
                    ?.takeIf { MimeUtils.typeFromMime(it) != null }
                    ?: MimeUtils.mimeFromName(response.request.url.encodedPath)
                    ?: return Result.Failure(
                        "This link didn't return an image or video. It may point to a web page or a login screen, " +
                            "which Story Saver can't download from."
                    )
                val actual = MimeUtils.typeFromMime(mime)
                if (actual != expected) {
                    return Result.Failure(
                        "This link is a${if (actual == MediaType.IMAGE) "n image" else " video"}, but " +
                            "${expected.label} is selected. Switch the type above and try again."
                    )
                }

                val total = body.contentLength()
                cacheDir.mkdirs()
                if (total > 0 && cacheDir.usableSpace < total + SPACE_MARGIN) {
                    return Result.Failure("Not enough free storage to download this file.")
                }
                val file = File(cacheDir, "pending_${System.nanoTime()}.${MimeUtils.extensionForMime(mime)}")
                tmp = file
                val written = body.byteStream().use { input ->
                    file.outputStream().use { output -> StreamCopier.copy(input, output, total, onProgress) }
                }
                if (written <= 0) {
                    file.delete()
                    return Result.Failure("The server sent an empty file.")
                }
                if (total > 0 && written != total) {
                    file.delete()
                    return Result.Failure("The download was interrupted before it finished. Please try again.")
                }
                tmp = null // keep it
                return Result.Success(file, mime, written)
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: UnknownHostException) {
            return Result.Failure("No internet connection, or the server couldn't be found.")
        } catch (e: SocketTimeoutException) {
            return Result.Failure("The server took too long to respond. Check your connection and try again.")
        } catch (e: SSLException) {
            return Result.Failure("A secure connection to the server couldn't be established.")
        } catch (e: InterruptedIOException) {
            return Result.Failure("The download was interrupted. Please try again.")
        } catch (e: IOException) {
            val msg = e.message.orEmpty()
            return if (msg.contains("ENOSPC") || msg.contains("No space", ignoreCase = true)) {
                Result.Failure("Your device ran out of storage during the download.")
            } else {
                Result.Failure("The download failed: ${msg.ifBlank { "network error" }}")
            }
        } catch (e: IllegalArgumentException) {
            return Result.Failure("That link can't be requested.")
        } finally {
            tmp?.delete()
        }
    }

    /** Removes leftover temp files from previous sessions. */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    private fun httpMessage(code: Int): String = when (code) {
        401, 403 -> "The server refused access (HTTP $code). The link may have expired or require you to be " +
            "logged in. Story Saver doesn't bypass login or privacy restrictions."
        404, 410 -> "This media is no longer available (HTTP $code). Media links from Instagram expire after a while."
        429 -> "The server is limiting requests right now (HTTP 429). Please wait and try again later."
        in 500..599 -> "The server had a problem (HTTP $code). Please try again later."
        else -> "The server returned an unexpected response (HTTP $code)."
    }

    private companion object {
        const val SPACE_MARGIN = 2L * 1024 * 1024
    }
}
