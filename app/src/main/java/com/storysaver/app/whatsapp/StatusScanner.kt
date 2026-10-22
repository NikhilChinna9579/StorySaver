package com.storysaver.app.whatsapp

import com.storysaver.app.util.MimeUtils

/** One entry in a folder tree, as reported by the storage provider. */
data class TreeEntry(
    val id: String,
    val name: String,
    val mime: String,
    val modified: Long,
    val size: Long,
    val isDir: Boolean,
)

/** Read access to a folder tree. Implemented over SAF on the phone and by fakes in tests. */
interface TreeReader {
    fun children(parentId: String): List<TreeEntry>

    /** Identifies a file's media type from its first bytes; null if unknown. */
    fun sniff(id: String): String?
}

data class ScannedFile(
    val id: String,
    val name: String,
    val mime: String,
    val modified: Long,
    val size: Long,
    /** Which status folder it came from, e.g. "WhatsApp/Media/.Statuses". */
    val folder: String,
)

data class ScanResult(
    val files: List<ScannedFile>,
    /** "name — reason" for every file that was found but not listed. */
    val skipped: List<String>,
    /** Status folders that were read (relative to the granted folder). */
    val folders: List<String>,
)

/**
 * Finds WhatsApp status media under whatever folder the user granted.
 *
 * Handles the user granting the `.Statuses` folder itself, or any parent of it
 * (`WhatsApp`, `Media`, `com.whatsapp`, `Android/media`, …), and collects *every* status folder
 * underneath: the main account, extra accounts (`WhatsApp/accounts/<id>/Media/.Statuses`) and
 * WhatsApp Business. The walk only enters WhatsApp-related folder names, so it never crawls
 * unrelated storage.
 */
object StatusScanner {

    const val STATUS_FOLDER = ".statuses"
    private const val MAX_DEPTH = 7

    fun scan(reader: TreeReader, rootId: String, rootName: String): ScanResult {
        val folders = findStatusFolders(reader, rootId, rootName)
        val targets = folders.ifEmpty { listOf(rootId to rootName) }

        val files = LinkedHashMap<String, ScannedFile>()
        val skipped = mutableListOf<String>()
        for ((folderId, label) in targets) {
            for (e in reader.children(folderId)) {
                if (e.isDir) continue
                if (e.name.equals(".nomedia", ignoreCase = true)) continue
                if (e.size == 0L) {
                    skipped += "${e.name} — empty file (download not finished)"
                    continue
                }
                val mime = MimeUtils.resolve(e.mime, e.name) ?: reader.sniff(e.id)
                if (mime == null || MimeUtils.typeFromMime(mime) == null) {
                    skipped += "${e.name} — not an image or video"
                    continue
                }
                val key = "${e.name}|${e.size}"
                val existing = files[key]
                if (existing == null || e.modified > existing.modified) {
                    files[key] = ScannedFile(e.id, e.name, mime, e.modified, e.size, label)
                }
            }
        }
        return ScanResult(
            files = files.values.sortedByDescending { it.modified },
            skipped = skipped,
            folders = targets.map { it.second },
        )
    }

    /** Breadth-first search for `.Statuses` folders, entering only WhatsApp-related names. */
    fun findStatusFolders(reader: TreeReader, rootId: String, rootName: String): List<Pair<String, String>> {
        if (rootName.equals(STATUS_FOLDER, ignoreCase = true)) return listOf(rootId to rootName)
        val found = mutableListOf<Pair<String, String>>()
        var level = listOf(rootId to rootName)
        var depth = 0
        while (level.isNotEmpty() && depth < MAX_DEPTH) {
            val next = mutableListOf<Pair<String, String>>()
            for ((id, path) in level) {
                for (child in reader.children(id)) {
                    if (!child.isDir) continue
                    val childPath = "$path/${child.name}"
                    when {
                        child.name.equals(STATUS_FOLDER, ignoreCase = true) -> found += child.id to childPath
                        isWalkable(child.name) -> next += child.id to childPath
                    }
                }
            }
            level = next
            depth++
        }
        return found
    }

    fun isWalkable(name: String): Boolean {
        val n = name.lowercase()
        if (n in WALKABLE || n == "whatsapp business") return true
        if (n.isNotEmpty() && n.all { it.isDigit() }) return true // accounts/<id>
        // "WhatsApp", "GBWhatsApp", … but not the chat-media folders "WhatsApp Images", "WhatsApp Video", …
        return n.contains("whatsapp") && !n.startsWith("whatsapp ")
    }

    private val WALKABLE = setOf("android", "media", "accounts", "com.whatsapp", "com.whatsapp.w4b")
}
