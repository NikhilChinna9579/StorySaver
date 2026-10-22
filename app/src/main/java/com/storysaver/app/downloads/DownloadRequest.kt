package com.storysaver.app.downloads

import com.storysaver.app.model.Platform
import com.storysaver.app.media.TrimRange
import org.json.JSONObject

/**
 * Everything a background download needs, serialisable to JSON so WorkManager can persist it
 * (downloads survive the app being closed). Pure Kotlin + org.json — unit-tested.
 */
sealed interface DownloadRequest {
    val title: String
    val platform: Platform
    val audioOnly: Boolean
    /** Readable file name without extension; null = generated. */
    val fileBase: String?
    /** Keep only this part of a video/audio; null = the whole file. */
    val trim: TrimRange?

    /** A single media file at a URL (Instagram items, direct CDN links). */
    data class Direct(
        val url: String,
        override val title: String,
        override val platform: Platform,
        /** Save only the sound of a video, as .m4a. */
        override val audioOnly: Boolean = false,
        override val fileBase: String? = null,
        override val trim: TrimRange? = null,
    ) : DownloadRequest

    /**
     * A file already on the phone (e.g. one Story Saver saved earlier) to save a trimmed copy of.
     * [uri] must stay readable by the app (its own MediaStore files, or a persisted folder).
     */
    data class Local(
        val uri: String,
        val mime: String,
        override val title: String,
        override val platform: Platform,
        override val audioOnly: Boolean = false,
        override val fileBase: String? = null,
        override val trim: TrimRange? = null,
    ) : DownloadRequest

    /** A YouTube download: one stream, or video + audio streams to merge. */
    data class YouTube(
        override val title: String,
        val videoUrl: String,
        val audioUrl: String?,
        val videoLength: Long,
        val audioLength: Long,
        val durationSeconds: Long,
        val qualityLabel: String,
        /** [videoUrl] is an audio stream saved as .m4a. */
        override val audioOnly: Boolean = false,
        override val fileBase: String? = null,
        override val trim: TrimRange? = null,
    ) : DownloadRequest {
        override val platform: Platform get() = Platform.YOUTUBE
    }

    fun toJson(): String = when (this) {
        is Direct -> JSONObject()
            .put("kind", "direct").put("url", url).put("title", title)
            .put("platform", platform.name).put("audioOnly", audioOnly)
            .put("fileBase", fileBase ?: JSONObject.NULL)
        is Local -> JSONObject()
            .put("kind", "local").put("uri", uri).put("mime", mime).put("title", title)
            .put("platform", platform.name).put("audioOnly", audioOnly)
            .put("fileBase", fileBase ?: JSONObject.NULL)
        is YouTube -> JSONObject()
            .put("kind", "youtube").put("title", title).put("videoUrl", videoUrl)
            .put("audioUrl", audioUrl ?: JSONObject.NULL)
            .put("videoLength", videoLength).put("audioLength", audioLength)
            .put("duration", durationSeconds).put("label", qualityLabel).put("audioOnly", audioOnly)
            .put("fileBase", fileBase ?: JSONObject.NULL)
    }.apply {
        trim?.let { put("trimStart", it.startMs).put("trimEnd", it.endMs) }
    }.toString()

    companion object {
        fun fromJson(json: String): DownloadRequest? = runCatching {
            val o = JSONObject(json)
            when (o.getString("kind")) {
                "direct" -> Direct(
                    url = o.getString("url"),
                    title = o.optString("title", "Download"),
                    platform = runCatching { Platform.valueOf(o.getString("platform")) }.getOrDefault(Platform.SHARED),
                    audioOnly = o.optBoolean("audioOnly", false),
                    fileBase = o.optStringOrNull("fileBase"),
                    trim = o.trim(),
                )
                "local" -> Local(
                    uri = o.getString("uri"),
                    mime = o.getString("mime"),
                    title = o.optString("title", "Clip"),
                    platform = runCatching { Platform.valueOf(o.getString("platform")) }.getOrDefault(Platform.SHARED),
                    audioOnly = o.optBoolean("audioOnly", false),
                    fileBase = o.optStringOrNull("fileBase"),
                    trim = o.trim(),
                )
                "youtube" -> YouTube(
                    title = o.optString("title", "YouTube video"),
                    videoUrl = o.getString("videoUrl"),
                    audioUrl = if (o.isNull("audioUrl")) null else o.getString("audioUrl"),
                    videoLength = o.optLong("videoLength", -1),
                    audioLength = o.optLong("audioLength", -1),
                    durationSeconds = o.optLong("duration", 0),
                    qualityLabel = o.optString("label", ""),
                    audioOnly = o.optBoolean("audioOnly", false),
                    fileBase = o.optStringOrNull("fileBase"),
                    trim = o.trim(),
                )
                else -> null
            }
        }.getOrNull()

        private fun JSONObject.trim(): TrimRange? {
            if (!has("trimStart") || !has("trimEnd")) return null
            val start = optLong("trimStart", -1)
            val end = optLong("trimEnd", -1)
            return if (start >= 0 && end > start) TrimRange(start, end) else null
        }

        private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
    }
}
