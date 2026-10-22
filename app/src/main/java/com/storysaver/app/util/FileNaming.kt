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
