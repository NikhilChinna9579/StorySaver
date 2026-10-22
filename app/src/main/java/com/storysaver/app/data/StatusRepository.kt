package com.storysaver.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import com.storysaver.app.model.MediaType
import com.storysaver.app.util.MimeUtils
import com.storysaver.app.whatsapp.StatusScanner
import com.storysaver.app.whatsapp.TreeEntry
import com.storysaver.app.whatsapp.TreeReader
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
 * The app never reads WhatsApp's private storage. It only reads the folder the user explicitly
 * selects in the system picker; WhatsApp keeps statuses it has downloaded in hidden `.Statuses`
 * folders in shared storage, and [StatusScanner] finds all of them under that folder.
 */
class StatusRepository(private val context: Context) {

    enum class Variant(val label: String) { WHATSAPP("WhatsApp"), BUSINESS("WhatsApp Business") }

    /**
     * Where the folder picker opens: the visible "WhatsApp" folder (not the hidden `.Statuses`,
     * which many pickers don't display). Only a hint — if the path doesn't exist on this phone,
     * the picker opens at its default location.
     */
    fun suggestedPickerStart(variant: Variant): Uri {
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            when (variant) {
                Variant.WHATSAPP -> "Android/media/com.whatsapp/WhatsApp"
                Variant.BUSINESS -> "Android/media/com.whatsapp.w4b/WhatsApp Business"
            }
        } else {
            when (variant) {
                Variant.WHATSAPP -> "WhatsApp"
                Variant.BUSINESS -> "WhatsApp Business"
            }
        }
        return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:$path")
    }

    fun persistAccess(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun hasAccess(tree: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission }

    /** Everything found, so the UI can explain what is and isn't shown. */
    data class Scan(
        val items: List<StatusItem>,
        val skipped: List<String>,
        val folders: List<String>,
    ) {
        fun of(type: MediaType) = items.filter { it.mediaType == type }
    }

    suspend fun scan(tree: Uri): Scan = withContext(Dispatchers.IO) {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val reader = SafTreeReader(tree)
        val result = StatusScanner.scan(reader, rootId, displayNameOf(rootId))
        Scan(
            items = result.files.map {
                StatusItem(DocumentsContract.buildDocumentUriUsingTree(tree, it.id), it.name, it.mime, it.modified, it.size)
            },
            skipped = result.skipped,
            folders = result.folders,
        )
    }

    /** "primary:Android/media/com.whatsapp/WhatsApp" → "WhatsApp". */
    private fun displayNameOf(docId: String): String =
        docId.substringAfterLast('/').substringAfterLast(':').ifEmpty { "Selected folder" }

    private inner class SafTreeReader(private val tree: Uri) : TreeReader {
        override fun children(parentId: String): List<TreeEntry> {
            val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE,
            )
            val out = mutableListOf<TreeEntry>()
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val mime = c.getString(2).orEmpty()
                    out += TreeEntry(
                        id = id,
                        name = c.getString(1).orEmpty(),
                        mime = mime,
                        modified = if (c.isNull(3)) 0L else c.getLong(3),
                        size = if (c.isNull(4)) -1L else c.getLong(4),
                        isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                    )
                }
            }
            return out
        }

        override fun sniff(id: String): String? = try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
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
    }

    private companion object {
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
