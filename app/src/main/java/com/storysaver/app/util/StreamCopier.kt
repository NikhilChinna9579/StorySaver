package com.storysaver.app.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream

object Progress {
    /** Fraction 0..1, or null when the total size is unknown (indeterminate). */
    fun fraction(done: Long, total: Long): Float? {
        if (total <= 0L) return null
        return (done.toDouble() / total.toDouble()).coerceIn(0.0, 1.0).toFloat()
    }
}

object StreamCopier {
    private const val BUFFER = 64 * 1024

    /**
     * Copies [input] to [output], reporting progress at most once per percent,
     * and stops promptly if the calling coroutine is cancelled.
     * @return number of bytes copied.
     */
    suspend fun copy(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (Float?) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER)
        var copied = 0L
        var lastPercent = Int.MIN_VALUE
        onProgress(Progress.fraction(0, totalBytes))
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
            val f = Progress.fraction(copied, totalBytes)
            val percent = f?.let { (it * 100).toInt() } ?: -1
            if (percent != lastPercent) {
                lastPercent = percent
                onProgress(f)
            }
        }
        output.flush()
        return copied
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "Unknown size"
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
    return String.format(java.util.Locale.US, "%.2f GB", mb / 1024.0)
}
