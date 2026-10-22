package com.storysaver.app.youtube

/** A stream as reported by the extractor, reduced to what quality selection needs. */
data class StreamDesc(
    val url: String,
    /** "video/mp4", "audio/mp4", "video/webm", … */
    val mime: String,
    val codec: String?,
    val height: Int,
    val fps: Int,
    val bitrate: Int,
    val contentLength: Long,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    /** Plain HTTP file (not a DASH/HLS manifest). */
    val progressive: Boolean,
    /** Original-language audio track (false for dubbed / descriptive tracks). */
    val originalAudio: Boolean = true,
)

/** One choice shown to the user. */
data class YtQuality(
    val label: String,
    val height: Int,
    val fps: Int,
    /** Video stream, or a combined video+audio stream when [audio] is null. */
    val video: StreamDesc,
    /** Separate audio stream to merge in; null when [video] already contains audio. */
    val audio: StreamDesc?,
) {
    val needsMerge: Boolean get() = audio != null

    /** Estimated download size in bytes, or -1 if unknown. */
    val approxBytes: Long
        get() {
            val v = video.contentLength
            val a = audio?.contentLength ?: 0
            return if (v > 0 && (audio == null || a > 0)) v + a else -1
        }
}

/**
 * Builds the resolution list (pure, unit-tested).
 *
 * The phone merges with Android's MediaMuxer into an .mp4, which supports H.264 video with AAC
 * audio everywhere. So we offer:
 *  - combined MP4 streams as-is (YouTube usually provides these only at 360p);
 *  - H.264 (avc1) MP4 video-only streams, each paired with the best AAC (m4a) audio track.
 * VP9/AV1-only resolutions (1440p and up) are not offered because they can't be merged into MP4
 * on every phone.
 */
object YouTubeFormats {

    fun qualities(streams: List<StreamDesc>): List<YtQuality> {
        val usable = streams.filter { it.progressive && it.url.isNotBlank() }

        val bestAudio = usable
            .filter { it.hasAudio && !it.hasVideo && it.mime == "audio/mp4" && isAac(it.codec) }
            .sortedWith(compareByDescending<StreamDesc> { it.originalAudio }.thenByDescending { it.bitrate })
            .firstOrNull()

        val byHeight = LinkedHashMap<Int, YtQuality>()

        // 1) Combined streams need no merging — preferred at the same height.
        usable.filter { it.hasVideo && it.hasAudio && it.mime == "video/mp4" && it.height > 0 }
            .sortedWith(compareByDescending<StreamDesc> { it.fps }.thenByDescending { it.bitrate })
            .forEach { s -> byHeight.putIfAbsent(s.height, YtQuality(label(s.height, s.fps), s.height, s.fps, s, null)) }

        // 2) H.264 video-only + AAC audio.
        if (bestAudio != null) {
            usable.filter { it.hasVideo && !it.hasAudio && it.mime == "video/mp4" && isH264(it.codec) && it.height > 0 }
                .sortedWith(compareByDescending<StreamDesc> { it.fps }.thenByDescending { it.bitrate })
                .forEach { s ->
                    byHeight.putIfAbsent(s.height, YtQuality(label(s.height, s.fps), s.height, s.fps, s, bestAudio))
                }
        }

        return byHeight.values.sortedWith(compareByDescending<YtQuality> { it.height }.thenByDescending { it.fps })
    }

    /** The option to preselect: the highest at or below [preferHeight], else the lowest. */
    fun defaultChoice(options: List<YtQuality>, preferHeight: Int = 720): YtQuality? =
        options.firstOrNull { it.height <= preferHeight } ?: options.lastOrNull()

    fun label(height: Int, fps: Int): String = if (fps > 30) "${height}p$fps" else "${height}p"

    private fun isH264(codec: String?) = codec == null || codec.startsWith("avc1", ignoreCase = true)
    private fun isAac(codec: String?) = codec == null || codec.startsWith("mp4a", ignoreCase = true)
}
