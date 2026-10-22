package com.storysaver.app.media

/**
 * The part of a video or audio file to keep, in milliseconds. Pure Kotlin — unit-tested.
 */
data class TrimRange(val startMs: Long, val endMs: Long) {

    val lengthMs: Long get() = endMs - startMs

    /** "0m05s-0m20s" — used in file names (no characters that are illegal in file names). */
    fun fileLabel(): String = "${stamp(startMs)}-${stamp(endMs)}"

    companion object {
        /** Shortest clip we allow. */
        const val MIN_LENGTH_MS = 1_000L

        /**
         * Cleans up a range picked in the UI for a file of [durationMs] (≤ 0 = unknown):
         * clamps to the file, keeps it at least [MIN_LENGTH_MS] long, and returns null when the
         * range is effectively the whole file (nothing to trim).
         */
        fun normalize(startMs: Long, endMs: Long, durationMs: Long): TrimRange? {
            val known = durationMs > 0
            var start = startMs.coerceAtLeast(0)
            var end = if (known) endMs.coerceAtMost(durationMs) else endMs
            if (end <= start) end = start + MIN_LENGTH_MS
            if (end - start < MIN_LENGTH_MS) end = start + MIN_LENGTH_MS
            if (known && end > durationMs) {
                end = durationMs
                start = (end - MIN_LENGTH_MS).coerceAtLeast(0)
            }
            // Within a quarter second of both ends → the user kept everything.
            if (known && start <= 250 && end >= durationMs - 250) return null
            return TrimRange(start, end)
        }

        private fun stamp(ms: Long): String {
            val total = ms / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%dh%02dm%02ds".format(h, m, s) else "%dm%02ds".format(m, s)
        }

        /** "1:05.3", "1:02:05.3" — for the trim screen. */
        fun clock(ms: Long): String {
            val safe = ms.coerceAtLeast(0)
            val tenths = (safe % 1000) / 100
            val total = safe / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d.%d".format(h, m, s, tenths) else "%d:%02d.%d".format(m, s, tenths)
        }

        /** Parses [clock] output back to ms (tests read the screen this way). */
        fun parseClock(text: String): Long? {
            val parts = text.trim().split(':')
            if (parts.size !in 2..3) return null
            val sec = parts.last().toDoubleOrNull() ?: return null
            val nums = parts.dropLast(1).map { it.toLongOrNull() ?: return null }
            val whole = nums.fold(0L) { acc, n -> acc * 60 + n } * 60
            return Math.round((whole + sec) * 1000)
        }

        /** Trimming works by lossless remuxing into MP4/M4A, so the source must be MP4-family. */
        fun canTrim(mime: String?): Boolean = mime in TRIMMABLE

        private val TRIMMABLE = setOf("video/mp4", "audio/mp4", "audio/x-m4a", "audio/m4a", "audio/aac")
    }
}
