package com.storysaver.app.youtube

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class ChunkedDownloaderTest {

    private val server = MockWebServer()
    private val client = OkHttpClient()
    private val data = ByteArray(1_000_003) { (it * 31 % 251).toByte() }
    private lateinit var dir: File
    private val requests = AtomicInteger()

    @Before fun setUp() { dir = Files.createTempDirectory("chunks").toFile() }

    @After fun tearDown() { server.shutdown(); dir.deleteRecursively() }

    /** A server that honours Range, optionally failing some requests. */
    private fun rangeServer(failFirstN: Int = 0, cutShortOnce: Boolean = false, status403: Boolean = false) {
        var cut = cutShortOnce
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val n = requests.incrementAndGet()
                if (status403) return MockResponse().setResponseCode(403)
                if (n <= failFirstN) return MockResponse().setResponseCode(503)
                assertEquals("Test-UA", request.getHeader("User-Agent"))
                val (s, e) = request.getHeader("Range")!!.removePrefix("bytes=").split('-').map { it.toLong() }
                val end = minOf(e, data.size - 1L)
                var body = data.copyOfRange(s.toInt(), end.toInt() + 1)
                if (cut && s > 0) { cut = false; body = body.copyOf(body.size / 2) } // drop half a chunk once
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $s-$end/${data.size}")
                    .setBody(Buffer().write(body))
            }
        }
        server.start()
    }

    private fun downloader() = ChunkedDownloader(client, chunkSize = 300_000, retries = 3)

    @Test fun downloadsInChunksAndReassembles() = runBlocking {
        rangeServer()
        val out = File(dir, "a.mp4")
        var last = 0L
        val n = downloader().download(server.url("/v").toString(), "Test-UA", out) { done, total ->
            assertTrue(done >= last); last = done
            assertTrue(total == -1L || total == data.size.toLong())
        }
        assertEquals(data.size.toLong(), n)
        assertArrayEquals(data, out.readBytes())
        assertEquals(4, requests.get()) // ceil(1_000_003 / 300_000)
    }

    @Test fun knownLengthIsUsedForRanges() = runBlocking {
        rangeServer()
        val out = File(dir, "b.mp4")
        downloader().download(server.url("/v").toString(), "Test-UA", out, knownLength = data.size.toLong())
        assertArrayEquals(data, out.readBytes())
    }

    @Test fun retriesServerErrors() = runBlocking {
        rangeServer(failFirstN = 2)
        val out = File(dir, "c.mp4")
        downloader().download(server.url("/v").toString(), "Test-UA", out)
        assertArrayEquals(data, out.readBytes())
    }

    @Test fun retriesAChunkThatWasCutShort() = runBlocking {
        rangeServer(cutShortOnce = true)
        val out = File(dir, "d.mp4")
        downloader().download(server.url("/v").toString(), "Test-UA", out)
        assertArrayEquals(data, out.readBytes()) // the partial bytes were rolled back, not duplicated
    }

    @Test fun forbiddenFailsImmediatelyAndCleansUp() = runBlocking {
        rangeServer(status403 = true)
        val out = File(dir, "e.mp4")
        try {
            downloader().download(server.url("/v").toString(), "Test-UA", out)
            fail("expected 403")
        } catch (e: ChunkedDownloader.HttpError) {
            assertEquals(403, e.code)
        }
        assertEquals(1, requests.get()) // no pointless retries on 403
        assertFalse(out.exists())
    }

    @Test fun serverIgnoringRangeStillWorks() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody(Buffer().write(data))
        }
        server.start()
        val out = File(dir, "f.mp4")
        downloader().download(server.url("/v").toString(), "Test-UA", out)
        assertArrayEquals(data, out.readBytes())
    }

    @Test fun emptyResponseIsAnError() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody("")
        }
        server.start()
        val out = File(dir, "g.mp4")
        try {
            downloader().download(server.url("/v").toString(), "Test-UA", out)
            fail("expected error")
        } catch (e: IOException) { /* expected */ }
        assertFalse(out.exists())
    }

    @Test fun parsesContentRange() {
        assertEquals(1234, ChunkedDownloader.parseTotal("bytes 0-99/1234"))
        assertEquals(-1, ChunkedDownloader.parseTotal("bytes 0-99/*"))
        assertEquals(-1, ChunkedDownloader.parseTotal(null))
    }
}
