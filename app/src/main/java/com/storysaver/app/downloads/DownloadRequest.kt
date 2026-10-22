package com.storysaver.app.downloads

import com.storysaver.app.model.Platform
import org.json.JSONObject

/**
 * Everything a background download needs, serialisable to JSON so WorkManager can persist it
 * (downloads survive the app being closed). Pure Kotlin + org.json — unit-tested.
 */
sealed interface DownloadRequest {
    val title: String
    val platform: Platform
    val audioOnly: Boolean

    /** A single media file at a URL (Instagram items, direct CDN links). */
    data class Direct(
        val url: String,
        override val title: String,
        override val platform: Platform,
        /** Save only the sound of a video, as .m4a. */
        override val audioOnly: Boolean = false,
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
    ) : DownloadRequest {
        override val platform: Platform get() = Platform.YOUTUBE
    }

    fun toJson(): String = when (this) {
        is Direct -> JSONObject()
            .put("kind", "direct").put("url", url).put("title", title)
            .put("platform", platform.name).put("audioOnly", audioOnly)
        is YouTube -> JSONObject()
            .put("kind", "youtube").put("title", title).put("videoUrl", videoUrl)
            .put("audioUrl", audioUrl ?: JSONObject.NULL)
            .put("videoLength", videoLength).put("audioLength", audioLength)
            .put("duration", durationSeconds).put("label", qualityLabel).put("audioOnly", audioOnly)
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
                )
                else -> null
            }
        }.getOrNull()
    }
}
