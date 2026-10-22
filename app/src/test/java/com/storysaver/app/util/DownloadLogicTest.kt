package com.storysaver.app.util

import com.storysaver.app.model.MediaType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class DownloadLogicTest {

    @Test fun progressFraction() {
        assertNull(Progress.fraction(10, -1))
        assertNull(Progress.fraction(10, 0))
        assertEquals(0.5f, Progress.fraction(50, 100)!!, 0.0001f)
        assertEquals(1f, Progress.fraction(150, 100)!!, 0.0001f)
    }

    @Test fun copyPreservesBytesAndReportsCompletion() = runBlocking {
        val data = ByteArray(300_000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val reports = mutableListOf<Float?>()
        val copied = StreamCopier.copy(ByteArrayInputStream(data), out, data.size.toLong()) { reports += it }
        assertEquals(data.size.toLong(), copied)
        assertArrayEquals(data, out.toByteArray())
        assertEquals(1f, reports.last()!!, 0.0001f)
        assertTrue("progress should be throttled", reports.size <= 102)
    }

    @Test fun copyWithUnknownSizeReportsIndeterminate() = runBlocking {
        val reports = mutableListOf<Float?>()
        StreamCopier.copy(ByteArrayInputStream(ByteArray(10)), ByteArrayOutputStream(), -1) { reports += it }
        assertTrue(reports.all { it == null })
    }

    @Test fun copyStopsWhenCancelled() = runBlocking {
        val job = Job()
        var reads = 0
        val endless = object : InputStream() {
            override fun read(): Int = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (++reads == 3) job.cancel()
                return len
            }
        }
        try {
            withContext(job) { StreamCopier.copy(endless, ByteArrayOutputStream(), -1) {} }
            fail("expected cancellation")
        } catch (_: CancellationException) {
            assertTrue(reads in 3..4)
        }
    }

    @Test fun mimeHelpers() {
        assertEquals("image/jpeg", MimeUtils.normalize("Image/JPEG; charset=binary"))
        assertEquals(MediaType.VIDEO, MimeUtils.typeFromMime("video/mp4"))
        assertNull(MimeUtils.typeFromMime("text/html"))
        assertEquals("jpg", MimeUtils.extensionOf("https://x/y/z.JPG?a=b"))
        assertNull(MimeUtils.extensionOf("https://x/y/z"))
        // Providers often report octet-stream for hidden folders: fall back to the name.
        assertEquals("video/mp4", MimeUtils.resolve("application/octet-stream", "abc.mp4"))
        assertEquals("image/png", MimeUtils.resolve("image/png", "abc.mp4"))
        assertNull(MimeUtils.resolve(null, ".nomedia"))
    }

    @Test fun sniffDetectsMediaFromHeaderBytes() {
        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
        assertEquals("image/jpeg", MimeUtils.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals("image/png", MimeUtils.sniff(bytes(0x89, 0x50, 0x4E, 0x47)))
        assertEquals("image/webp", MimeUtils.sniff(ascii("RIFF") + bytes(0, 0, 0, 0) + ascii("WEBP")))
        assertEquals("video/mp4", MimeUtils.sniff(bytes(0, 0, 0, 0x20) + ascii("ftypisom")))
        assertEquals("image/heic", MimeUtils.sniff(bytes(0, 0, 0, 0x18) + ascii("ftypheic")))
        assertNull(MimeUtils.sniff(ascii("hello world!")))
        assertNull(MimeUtils.sniff(ByteArray(0)))
    }

    @Test fun formatBytesIsReadable() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(2048))
        assertEquals("1.5 MB", formatBytes(1_572_864))
        assertEquals("Unknown size", formatBytes(-1))
    }
}
