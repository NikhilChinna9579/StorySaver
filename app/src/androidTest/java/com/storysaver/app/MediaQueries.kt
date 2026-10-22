package com.storysaver.app

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry

/** Gallery lookups used by the end-to-end tests. */
object MediaQueries {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    val VIDEOS: Uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val AUDIO: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    fun ids(collection: Uri, namePrefix: String): Set<Long> =
        ctx.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?", arrayOf("$namePrefix%"), null,
        )?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptySet()

    /** Polls until a new file appears (works even after the app's screen is gone). */
    fun waitForNew(collection: Uri, namePrefix: String, before: Set<Long>, timeoutMs: Long): Long? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            (ids(collection, namePrefix) - before).firstOrNull()?.let { return it }
            Thread.sleep(1_000)
        }
        return null
    }

    fun uri(collection: Uri, id: Long): Uri = Uri.withAppendedPath(collection, id.toString())

    /** MIME types of the tracks inside a saved file. */
    fun trackMimes(uri: Uri): List<String> {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(ctx, uri, null)
            (0 until ex.trackCount).mapNotNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME) }
        } finally {
            ex.release()
        }
    }
}
