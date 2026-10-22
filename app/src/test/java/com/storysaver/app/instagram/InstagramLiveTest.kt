package com.storysaver.app.instagram

import com.storysaver.app.util.MimeUtils
import com.storysaver.app.util.UrlValidator
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hits the real Instagram with the app's real extractor. Runs only when LIVE_INSTAGRAM=1
 * (CI sets it in a separate, non-blocking step) and writes a report to build/live-instagram.txt.
 * Extra links can be passed in LIVE_INSTAGRAM_URLS (space separated).
 */
class InstagramLiveTest {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .followSslRedirects(false).build()

    @Test fun resolvesRealLinks() {
        assumeTrue(System.getenv("LIVE_INSTAGRAM") == "1")
        val urls = listOf("https://www.instagram.com/reel/DeyQf_PzHlD/?exln=dmcxMDJ0YmxzMnFn") +
            System.getenv("LIVE_INSTAGRAM_URLS").orEmpty().split(' ').filter { it.isNotBlank() }
        val report = StringBuilder()
        var okCount = 0
        val extractor = InstagramExtractor(client)
        for (url in urls) {
            val check = UrlValidator.check(url)
            val result = extractor.resolve(check)
            report.append("LINK $url\n  classified: $check\n")
            when (result) {
                is InstagramExtractor.Result.Failure -> report.append("  FAILED (${result.reason}): ${result.message}\n")
                is InstagramExtractor.Result.Success -> {
                    report.append("  owner=@${result.post.owner} items=${result.post.items.size}\n")
                    for (item in result.post.items) {
                        val (code, type, magic) = head(item.url)
                        val sniffed = MimeUtils.sniff(magic)
                        val good = code in 200..299 && MimeUtils.typeFromMime(sniffed) == item.type
                        if (good) okCount++
                        report.append("  item ${item.index}: ${item.type} http=$code ctype=$type sniff=$sniffed -> ${if (good) "OK" else "BAD"}\n")
                    }
                }
            }
        }
        File("build").mkdirs()
        File("build/live-instagram.txt").writeText(report.toString())
        println(report)
        assertTrue("No media could be downloaded:\n$report", okCount > 0)
    }

    /** Downloads the first bytes the same way the app does (same user agent, no cookies). */
    private fun head(url: String): Triple<Int, String?, ByteArray> {
        val req = Request.Builder().url(url)
            .header("User-Agent", InstagramExtractor.APP_UA)
            .header("Range", "bytes=0-63").build()
        client.newCall(req).execute().use { r ->
            val bytes = r.body?.byteStream()?.use { it.readNBytes(64) } ?: ByteArray(0)
            return Triple(r.code, r.header("Content-Type"), bytes)
        }
    }
}
