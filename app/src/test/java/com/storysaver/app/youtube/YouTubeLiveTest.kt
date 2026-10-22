package com.storysaver.app.youtube

import com.storysaver.app.util.MimeUtils
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real YouTube, real app code. Runs only with LIVE_YOUTUBE=1 (a separate, non-blocking CI step).
 * Writes build/live-youtube.txt. Extra links: LIVE_YOUTUBE_URLS (space separated).
 */
class YouTubeLiveTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    @Test fun resolvesAndDownloadsRealVideos() {
        assumeTrue(System.getenv("LIVE_YOUTUBE") == "1")
        val links = listOf(
            "https://youtu.be/jNQXAC9IVRw?si=test",          // "Me at the zoo", 19 s
            "https://www.youtube.com/watch?v=BaW_jenozKc",   // youtube-dl's public test video, 10 s
        ) + System.getenv("LIVE_YOUTUBE_URLS").orEmpty().split(' ').filter { it.isNotBlank() }

        val report = StringBuilder()
        var resolvedOk = 0
        var fullDownloadOk = false
        val extractor = YouTubeExtractor(client)
        for (url in links) {
            report.append("LINK $url\n")
            val link = YouTubeLinks.parse(url) as? YtLink.Video
            if (link == null) { report.append("  not a video link\n"); continue }
            val t0 = System.currentTimeMillis()
            when (val r = extractor.resolve(link)) {
                is YouTubeExtractor.Result.Failure -> report.append("  FAILED: ${r.message}\n")
                is YouTubeExtractor.Result.Success -> {
                    val v = r.video
                    report.append("  '${v.title}' by ${v.channel}, ${v.durationSeconds}s, ${System.currentTimeMillis() - t0} ms\n")
                    var allStreamsOk = true
                    for (q in v.qualities) {
                        val vh = head(q.video.url)
                        val ah = q.audio?.let { head(it.url) }
                        val ok = vh.first == 206 && MimeUtils.sniff(vh.second) == "video/mp4" &&
                            (ah == null || (ah.first == 206 && ah.second.size >= 8))
                        if (!ok) allStreamsOk = false
                        report.append("  ${q.label}${if (q.needsMerge) " (video+audio merge)" else " (combined)"} " +
                            "size=${q.approxBytes} video=${vh.first} audio=${ah?.first ?: "-"} -> ${if (ok) "OK" else "BAD"}\n")
                    }
                    if (allStreamsOk && v.qualities.isNotEmpty()) resolvedOk++

                    if (!fullDownloadOk) {
                        // Full chunked download of the smallest option, both parts.
                        val q = v.qualities.last()
                        val dir = File("build/live-yt").apply { mkdirs() }
                        runBlocking {
                            val dl = ChunkedDownloader(client, chunkSize = 256 * 1024)
                            val vf = File(dir, "v.mp4")
                            val n = dl.download(q.video.url, YouTubeExtractor.userAgentFor(q.video.url), vf, q.video.contentLength)
                            var line = "  full download ${q.label}: video $n bytes (expected ${q.video.contentLength})"
                            var ok = q.video.contentLength <= 0 || n == q.video.contentLength
                            q.audio?.let { a ->
                                val af = File(dir, "a.m4a")
                                val m = dl.download(a.url, YouTubeExtractor.userAgentFor(a.url), af, a.contentLength)
                                line += ", audio $m bytes (expected ${a.contentLength})"
                                ok = ok && (a.contentLength <= 0 || m == a.contentLength)
                            }
                            fullDownloadOk = ok
                            report.append("$line -> ${if (ok) "OK" else "BAD"}\n")
                        }
                    }
                }
            }
        }
        File("build").mkdirs()
        File("build/live-youtube.txt").writeText(report.toString())
        println(report)
        assertTrue("YouTube live check failed:\n$report", resolvedOk > 0 && fullDownloadOk)
    }

    private fun head(url: String): Pair<Int, ByteArray> {
        val req = Request.Builder().url(url)
            .header("User-Agent", YouTubeExtractor.userAgentFor(url))
            .header("Range", "bytes=0-63").build()
        return try {
            client.newCall(req).execute().use { r -> r.code to (r.body?.byteStream()?.use { it.readNBytes(64) } ?: ByteArray(0)) }
        } catch (e: Exception) {
            -1 to ByteArray(0)
        }
    }
}
