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

    /** Files in a StorySaver folder whose name matches the SQL LIKE pattern (e.g. "%instagram%"). */
    fun ids(collection: Uri, nameLike: String = "%"): Set<Long> =
        ctx.contentResolver.query(
            collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf(nameLike, "%StorySaver%"), null,
        )?.use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptySet()

    fun name(collection: Uri, id: Long): String? =
        ctx.contentResolver.query(uri(collection, id), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Polls until a new file appears (works even after the app's screen is gone). */
    fun waitForNew(collection: Uri, nameLike: String, before: Set<Long>, timeoutMs: Long): Long? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            (ids(collection, nameLike) - before).firstOrNull()?.let { return it }
            Thread.sleep(1_000)
        }
        return null
    }

    fun uri(collection: Uri, id: Long): Uri = Uri.withAppendedPath(collection, id.toString())

    /** Duration in ms of a saved video/audio file. */
    fun durationMs(uri: Uri): Long {
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(ctx, uri)
            mmr.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1
        } finally {
            mmr.release()
        }
    }

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
