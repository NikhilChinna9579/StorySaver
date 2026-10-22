package com.storysaver.app.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class YtVideo(
    val id: String,
    val title: String,
    val channel: String?,
    val durationSeconds: Long,
    val thumbnailUrl: String?,
    val qualities: List<YtQuality>,
)

/**
 * Looks up a YouTube video's downloadable streams with NewPipeExtractor (the open-source library
 * behind the NewPipe app), anonymously. No Android dependencies, so it is exercised in JVM tests.
 */
class YouTubeExtractor(private val client: OkHttpClient) {

    sealed interface Result {
        data class Success(val video: YtVideo) : Result
        data class Failure(val message: String) : Result
    }

    /** Blocking — call from a background thread. */
    fun resolve(link: YtLink.Video): Result {
        ensureInit(client)
        return try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, link.canonicalUrl)
            if (info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM) {
                return Result.Failure("This is a live stream. Live streams can't be downloaded — try again after it ends.")
            }
            val streams = info.videoStreams.map { it.toDesc() } +
                info.videoOnlyStreams.map { it.toDesc() } +
                info.audioStreams.map { it.toDesc() }
            val qualities = YouTubeFormats.qualities(streams)
            if (qualities.isEmpty()) {
                return Result.Failure("YouTube didn't offer a downloadable MP4 version of this video.")
            }
            Result.Success(
                YtVideo(
                    id = link.id,
                    title = info.name ?: "YouTube video",
                    channel = info.uploaderName,
                    durationSeconds = info.duration,
                    thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url,
                    qualities = qualities,
                )
            )
        } catch (e: SignInConfirmNotBotException) {
            Result.Failure(
                "YouTube is asking this network to sign in to confirm it's not a bot, so it won't share the video " +
                    "anonymously right now. Try again later, or switch between Wi-Fi and mobile data. " +
                    "Story Saver doesn't sign in to YouTube."
            )
        } catch (e: AgeRestrictedContentException) {
            Result.Failure("This video is age-restricted. YouTube only plays it for signed-in users, so it can't be downloaded here.")
        } catch (e: PrivateContentException) {
            Result.Failure("This video is private.")
        } catch (e: PaidContentException) {
            Result.Failure("This video is for paying members only, so it can't be downloaded.")
        } catch (e: YoutubeMusicPremiumContentException) {
            Result.Failure("This is YouTube Music Premium content, so it can't be downloaded.")
        } catch (e: GeographicRestrictionException) {
            Result.Failure("This video isn't available in your country.")
        } catch (e: ContentNotAvailableException) {
            Result.Failure("This video isn't available. It may have been removed, or it's not playable outside YouTube.")
        } catch (e: ReCaptchaException) {
            Result.Failure("YouTube is limiting requests from this network right now. Please wait a while and try again.")
        } catch (e: UnknownHostException) {
            Result.Failure("No internet connection, or YouTube couldn't be reached.")
        } catch (e: SocketTimeoutException) {
            Result.Failure("YouTube took too long to respond. Check your connection and try again.")
        } catch (e: IOException) {
            Result.Failure("Couldn't reach YouTube: ${e.message ?: "network error"}")
        } catch (e: Exception) {
            Result.Failure("Couldn't read this video from YouTube (${e.javaClass.simpleName}). If this keeps happening for every video, the app needs an update.")
        }
    }

    private fun VideoStream.toDesc() = StreamDesc(
        url = if (isUrl) content else "",
        mime = format?.mimeType ?: "",
        codec = codec,
        height = height.takeIf { it > 0 } ?: resolution?.substringBefore('p')?.toIntOrNull() ?: 0,
        fps = fps,
        bitrate = bitrate,
        contentLength = itagItem?.contentLength ?: -1,
        hasVideo = true,
        hasAudio = !isVideoOnly,
        progressive = deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP,
    )

    private fun AudioStream.toDesc() = StreamDesc(
        url = if (isUrl) content else "",
        mime = format?.mimeType ?: "",
        codec = codec,
        height = 0,
        fps = 0,
        bitrate = averageBitrate.takeIf { it > 0 } ?: bitrate,
        contentLength = itagItem?.contentLength ?: -1,
        hasVideo = false,
        hasAudio = true,
        progressive = deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP,
        originalAudio = audioTrackType == null || audioTrackType == AudioTrackType.ORIGINAL,
    )

    companion object {
        @Volatile private var initialized = false

        fun ensureInit(client: OkHttpClient) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    NewPipe.init(NewPipeDownloader(client))
                    initialized = true
                }
            }
        }

        /** Media requests to YouTube's servers must use the same client identity as the lookup. */
        fun userAgentFor(mediaUrl: String): String =
            if (YoutubeParsingHelper.isVisionOsStreamingUrl(mediaUrl)) {
                YoutubeParsingHelper.getVisionOsUserAgent(null)
            } else {
                NewPipeDownloader.USER_AGENT
            }
    }
}

/** OkHttp-backed network layer for NewPipeExtractor (mirrors the library's reference implementation). */
class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .addHeader("User-Agent", USER_AGENT)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString(),
            )
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:153.0) Gecko/20100101 Firefox/153.0"
    }
}
