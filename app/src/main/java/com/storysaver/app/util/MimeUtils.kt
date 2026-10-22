package com.storysaver.app.util

import com.storysaver.app.model.MediaType

/** Pure-Kotlin MIME helpers (no Android dependencies, so they are unit-testable). */
object MimeUtils {

    private val imageByExt = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "heic" to "image/heic",
        "heif" to "image/heif",
    )

    private val videoByExt = mapOf(
        "mp4" to "video/mp4",
        "m4v" to "video/mp4",
        "3gp" to "video/3gpp",
        "webm" to "video/webm",
        "mkv" to "video/x-matroska",
        "mov" to "video/quicktime",
    )

    private val extByMime: Map<String, String> = mapOf(
        "image/jpeg" to "jpg",
        "image/jpg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "video/mp4" to "mp4",
        "video/3gpp" to "3gp",
        "video/webm" to "webm",
        "video/x-matroska" to "mkv",
        "video/quicktime" to "mov",
        "audio/mp4" to "m4a",
        "audio/m4a" to "m4a",
        "audio/x-m4a" to "m4a",
        "audio/mpeg" to "mp3",
        "audio/aac" to "aac",
        "audio/webm" to "weba",
        "audio/ogg" to "ogg",
    )

    /** Lower-case extension of a file name or URL path, ignoring any query string. */
    fun extensionOf(nameOrPath: String): String? {
        val clean = nameOrPath.substringBefore('?').substringBefore('#')
        val last = clean.substringAfterLast('/')
        if (!last.contains('.')) return null
        return last.substringAfterLast('.').lowercase().ifEmpty { null }
    }

    fun mimeFromName(nameOrPath: String): String? =
        extensionOf(nameOrPath)?.let { imageByExt[it] ?: videoByExt[it] }

    /** Strips parameters (e.g. "; charset=utf-8") and lower-cases a Content-Type header. */
    fun normalize(contentType: String?): String? =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null }

    fun typeFromMime(mime: String?): MediaType? {
        val m = normalize(mime) ?: return null
        return when {
            m.startsWith("image/") -> MediaType.IMAGE
            m.startsWith("video/") -> MediaType.VIDEO
            else -> null
        }
    }

    fun typeFromPath(path: String): MediaType? = typeFromMime(mimeFromName(path))

    /** Best file extension for a MIME type; falls back to jpg / mp4 by top-level type. */
    fun extensionForMime(mime: String): String {
        val m = normalize(mime) ?: return "bin"
        extByMime[m]?.let { return it }
        return when {
            m.startsWith("image/") -> "jpg"
            m.startsWith("video/") -> "mp4"
            m.startsWith("audio/") -> "m4a"
            else -> "bin"
        }
    }

    fun isAudio(mime: String?): Boolean = normalize(mime)?.startsWith("audio/") == true

    /** Images, videos and audio — everything the app can save. */
    fun isSavable(mime: String?): Boolean = typeFromMime(mime) != null || isAudio(mime)

    /** Detects a media type from a file's first bytes (at least 12 needed for MP4/WebP). */
    fun sniff(header: ByteArray): String? {
        fun b(i: Int) = if (i < header.size) header[i].toInt() and 0xFF else -1
        fun ascii(from: Int, s: String) = s.indices.all { b(from + it) == s[it].code }
        return when {
            b(0) == 0xFF && b(1) == 0xD8 && b(2) == 0xFF -> "image/jpeg"
            b(0) == 0x89 && ascii(1, "PNG") -> "image/png"
            ascii(0, "GIF8") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(4, "ftyp") -> if (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1")) "image/heic" else "video/mp4"
            b(0) == 0x1A && b(1) == 0x45 && b(2) == 0xDF && b(3) == 0xA3 -> "video/webm"
            else -> null
        }
    }

    /**
     * Picks a usable MIME type: the reported one when it is an image/video,
     * otherwise one derived from the file name (providers often report
     * application/octet-stream for files in hidden folders).
     */
    fun resolve(reported: String?, fileName: String?): String? {
        val r = normalize(reported)
        if (typeFromMime(r) != null) return r
        return fileName?.let { mimeFromName(it) }
    }
}
