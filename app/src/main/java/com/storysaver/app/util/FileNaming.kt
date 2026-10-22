package com.storysaver.app.util

import com.storysaver.app.model.Platform
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Generates unique, filesystem-safe names for saved media. Pure Kotlin. */
object FileNaming {

    const val PREFIX = "StorySaver"

    /**
     * e.g. StorySaver_WA_20261022_134501_a1b2.jpg
     * The random suffix makes collisions practically impossible even for saves in the same second.
     */
    fun generate(
        platform: Platform,
        mimeType: String,
        timestampMillis: Long,
        suffix: String = randomSuffix(),
        timeZone: TimeZone = TimeZone.getDefault(),
    ): String {
        val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { this.timeZone = timeZone }
        val stamp = fmt.format(Date(timestampMillis))
        val ext = MimeUtils.extensionForMime(mimeType)
        return "${PREFIX}_${platform.tag}_${stamp}_${sanitize(suffix)}.$ext"
    }

    fun randomSuffix(): String = UUID.randomUUID().toString().replace("-", "").take(4)

    /** Replaces characters that are unsafe on FAT/ext4/SAF providers. */
    fun sanitize(raw: String): String {
        val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            .replace(Regex("_+"), "_")
            .trim('_', '.')
            .take(60)
        return cleaned.ifEmpty { "media" }
    }

    // ---------------- Readable names ----------------

    private const val MAX_CHARS = 100
    private const val MAX_BYTES = 180 // filesystem limit is 255 bytes; leave room for " (12).ext"

    /**
     * Makes a human title safe as a file name while keeping it readable: keeps letters in any
     * language, removes characters Android/FAT file systems reject (/ \ : * ? " < > |), control
     * characters and emoji, collapses spaces, and caps the length (in UTF-8 bytes too).
     */
    fun cleanTitle(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            when {
                cp in ILLEGAL -> sb.append(' ')
                Character.isISOControl(cp) -> sb.append(' ')
                cp > 0xFFFF -> {} // emoji and other astral symbols
                Character.getType(cp) == Character.OTHER_SYMBOL.toInt() && cp >= 0x2190 -> {} // dingbats, arrows, ✨
                cp == 0xFE0F || cp == 0x200D -> {} // emoji presentation / joiner
                else -> sb.appendCodePoint(cp)
            }
        }
        var t = sb.toString().replace(Regex("\\s+"), " ").trim().trim('.', ' ', '-', '_')
        if (t.length > MAX_CHARS) t = t.take(MAX_CHARS).trimEnd()
        while (t.toByteArray(Charsets.UTF_8).size > MAX_BYTES) t = t.dropLast(1)
        return t.trimEnd('.', ' ', '-', '_')
    }

    private val ILLEGAL = setOf('/', '\\', ':', '*', '?', '"', '<', '>', '|').map { it.code }.toSet()

    /** "base" + extension for [mime]; falls back to a generated name when the base is blank. */
    fun readable(base: String?, platform: Platform, mime: String, timestampMillis: Long): String {
        val clean = base?.let { cleanTitle(it) }.orEmpty()
        return if (clean.isEmpty()) generate(platform, mime, timestampMillis) else "$clean.${MimeUtils.extensionForMime(mime)}"
    }

    /** e.g. "ndtv_instagram_DeyQf_PzHlD", "ndtv_instagram_C9xYz_2_audio". */
    fun instagramBase(owner: String?, shortcode: String?, index: Int, count: Int, audio: Boolean): String =
        listOfNotNull(
            owner?.takeIf { it.isNotBlank() },
            "instagram",
            shortcode?.takeIf { it.isNotBlank() },
            (index + 1).takeIf { count > 1 }?.toString(),
            "audio".takeIf { audio },
        ).joinToString("_")

    /** e.g. "Me at the zoo - jawed (360p)"; audio: "Me at the zoo - jawed". */
    fun youtubeBase(title: String, channel: String?, quality: String?, audio: Boolean): String = buildString {
        append(title.ifBlank { "YouTube video" })
        if (!channel.isNullOrBlank() && !title.contains(channel, ignoreCase = true)) append(" - ").append(channel)
        if (!audio && !quality.isNullOrBlank()) append(" (").append(quality).append(')')
    }

    /**
     * Marks a trimmed copy: "ndtv_instagram_DeyQf_PzHlD_clip_0m05s-0m20s",
     * "Me at the zoo - jawed (360p) clip 0m05s-0m20s". Null/blank bases stay as they are.
     */
    fun withClip(base: String?, range: com.storysaver.app.media.TrimRange?): String? {
        if (range == null || base.isNullOrBlank()) return base
        return if (base.contains(' ')) "$base clip ${range.fileLabel()}" else "${base}_clip_${range.fileLabel()}"
    }

    /** e.g. "WhatsApp_status_2026-10-22_14-03-11" (when the status was posted/received). */
    fun whatsappBase(statusTimeMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).apply { this.timeZone = timeZone }
        return "WhatsApp_status_" + fmt.format(Date(statusTimeMillis))
    }

    /** A shared file keeps its own name (without extension) when it has a meaningful one. */
    fun sharedBase(originalName: String?, timestampMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): String {
        val stem = originalName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() && !it.matches(Regex("[0-9a-fA-F_-]{12,}")) }
        if (stem != null) return stem
        val fmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).apply { this.timeZone = timeZone }
        return "Shared_" + fmt.format(Date(timestampMillis))
    }

    /**
     * Returns [name] if it is free, otherwise "base (1).ext", "base (2).ext", …
     * [exists] is injected so this works for java.io.File and for tests.
     */
    fun dedupe(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..999) {
            val candidate = "$base ($i)$ext"
            if (!exists(candidate)) return candidate
        }
        return "${base}_${System.nanoTime()}$ext"
    }
}
