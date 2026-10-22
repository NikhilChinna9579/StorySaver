package com.storysaver.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.storysaver.app.util.MimeUtils
import java.io.File

/** Reads name / size / MIME for any file:// or content:// URI. */
object UriInfo {

    data class Meta(val name: String?, val size: Long, val mime: String?)

    fun meta(context: Context, uri: Uri): Meta {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return Meta(null, -1, null))
            return Meta(f.name, if (f.exists()) f.length() else -1, MimeUtils.mimeFromName(f.name))
        }
        val resolver = context.contentResolver
        var name: String? = null
        var size = -1L
        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (_: Exception) {
            // Some providers don't support queries; fall through to the descriptor check.
        }
        if (size <= 0) {
            size = try {
                resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (_: Exception) {
                -1L
            }
        }
        val reported = try { resolver.getType(uri) } catch (_: Exception) { null }
        return Meta(name, size, MimeUtils.resolve(reported, name))
    }

    fun exists(context: Context, uri: Uri): Boolean {
        if (uri.scheme == "file") return uri.path?.let { File(it).exists() } == true
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
        } catch (_: Exception) {
            false
        }
    }
}
