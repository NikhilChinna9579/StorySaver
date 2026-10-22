package com.storysaver.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.storysaver.app.model.MediaType
import com.storysaver.app.util.MimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A status file visible inside the folder the user granted. */
data class StatusItem(
    val uri: Uri,
    val name: String,
    val mimeType: String,
    val modified: Long,
    val size: Long,
) {
    val mediaType: MediaType? get() = MimeUtils.typeFromMime(mimeType)
}

/**
 * Lists WhatsApp status media through the Storage Access Framework.
 *
 * The app never reads WhatsApp's private storage. It only lists the folder the user
 * explicitly selects in the system picker (normally the hidden `.Statuses` folder that
 * WhatsApp keeps in shared storage for statuses the user has already viewed).
 */
class StatusRepository(private val context: Context) {

    private data class Child(
        val docId: String, val name: String, val mime: String, val modified: Long, val size: Long,
    ) {
        val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    enum class Variant(val label: String) { WHATSAPP("WhatsApp"), BUSINESS("WhatsApp Business") }

    /**
     * Where the folder picker should open. This is only a hint: if the path doesn't exist on
     * this phone (manufacturer differences, older WhatsApp), the picker opens at its default
     * location and the user navigates manually.
     */
    fun suggestedPickerStart(variant: Variant): Uri {
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (variant) {
                Variant.WHATSAPP -> "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"
                Variant.BUSINESS -> "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"
            }
        } else {
            when (variant) {
                Variant.WHATSAPP -> "WhatsApp/Media/.Statuses"
                Variant.BUSINESS -> "WhatsApp Business/Media/.Statuses"
            }
        }
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$path")
    }

    fun persistAccess(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun hasAccess(tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

    /** Everything found in the status folder, so the UI can explain what is and isn't shown. */
    data class Scan(
        val items: List<StatusItem>,
        /** Files that aren't images/videos, or are unfinished downloads. */
        val skipped: List<String>,
    ) {
        fun of(type: MediaType) = items.filter { it.mediaType == type }
    }

    /**
     * Lists every image and video in the granted folder, newest first. If the user picked a
     * parent folder (e.g. "WhatsApp" or "Media"), we step down into `.Statuses` automatically.
     * Files whose type can't be told from the name are identified by their first bytes.
     */
    suspend fun scan(tree: Uri): Scan = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val folderId = findStatusesFolder(tree, rootId, depth = 0) ?: rootId
        val items = mutableListOf<StatusItem>()
        val skipped = mutableListOf<String>()
        for (c in queryChildren(tree, folderId)) {
            if (c.isDir || c.name.equals(".nomedia", ignoreCase = true)) continue
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.docId)
            val mime = MimeUtils.resolve(c.mime, c.name) ?: sniffMime(uri)
            if (mime == null || MimeUtils.typeFromMime(mime) == null || c.size == 0L) {
                skipped += c.name
                continue
            }
            items += StatusItem(uri, c.name, mime, c.modified, c.size)
        }
        Scan(items.sortedByDescending { it.modified }, skipped)
    }

    /** Identifies JPEG / PNG / WebP / GIF / MP4-family files from their header bytes. */
    private fun sniffMime(uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val h = ByteArray(12)
            var n = 0
            while (n < h.size) {
                val r = input.read(h, n, h.size - n)
                if (r < 0) break
                n += r
            }
            MimeUtils.sniff(h.copyOf(n))
        }
    } catch (_: Exception) {
        null
    }

    /** Bounded search that only walks WhatsApp-like folder names, never the whole drive. */
    private fun findStatusesFolder(tree: Uri, docId: String, depth: Int): String? {
        if (docId.substringAfterLast('/').equals(".Statuses", ignoreCase = true)) return docId
        if (depth >= 4) return null
        val dirs = queryChildren(tree, docId).filter { it.isDir }
        dirs.firstOrNull { it.name.equals(".Statuses", ignoreCase = true) }?.let { return it.docId }
        for (d in dirs) {
            if (d.name.lowercase() in WALKABLE) {
                findStatusesFolder(tree, d.docId, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun queryChildren(tree: Uri, parentId: String): List<Child> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val out = mutableListOf<Child>()
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Child(
                    docId = c.getString(0) ?: continue,
                    name = c.getString(1).orEmpty(),
                    mime = c.getString(2).orEmpty(),
                    modified = if (c.isNull(3)) 0L else c.getLong(3),
                    size = if (c.isNull(4)) -1L else c.getLong(4),
                )
            }
        }
        return out
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        val WALKABLE = setOf(
            "android", "media", "com.whatsapp", "com.whatsapp.w4b", "whatsapp", "whatsapp business",
        )
    }
}
