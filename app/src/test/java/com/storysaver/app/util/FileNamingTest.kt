package com.storysaver.app.util

import com.storysaver.app.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class FileNamingTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private val t = 1_792_675_501_000L // 2026-10-22 13:25:01 UTC

    @Test fun generatesExpectedFormat() {
        val name = FileNaming.generate(Platform.WHATSAPP, "image/jpeg", t, suffix = "ab12", timeZone = utc)
        assertEquals("StorySaver_WA_20261022_132501_ab12.jpg", name)
    }

    @Test fun usesExtensionMatchingMime() {
        assertTrue(FileNaming.generate(Platform.INSTAGRAM, "video/mp4", t, "x", utc).endsWith(".mp4"))
        assertTrue(FileNaming.generate(Platform.INSTAGRAM, "image/webp", t, "x", utc).endsWith(".webp"))
        assertTrue(FileNaming.generate(Platform.SHARED, "video/quicktime", t, "x", utc).endsWith(".mov"))
        assertTrue(FileNaming.generate(Platform.SHARED, "image/x-unknown", t, "x", utc).endsWith(".jpg"))
    }

    @Test fun randomSuffixMakesNamesUnique() {
        val a = FileNaming.generate(Platform.WHATSAPP, "image/png", t, timeZone = utc)
        val b = FileNaming.generate(Platform.WHATSAPP, "image/png", t, timeZone = utc)
        assertNotEquals(a, b)
    }

    @Test fun sanitizeRemovesUnsafeCharacters() {
        assertEquals("a_b_c.jpg", FileNaming.sanitize("a/b:c.jpg"))
        assertEquals("media", FileNaming.sanitize("///"))
        assertEquals(60, FileNaming.sanitize("x".repeat(200)).length)
    }

    @Test fun dedupeAppendsCounter() {
        val taken = setOf("photo.jpg", "photo (1).jpg")
        assertEquals("photo (2).jpg", FileNaming.dedupe("photo.jpg") { it in taken })
        assertEquals("fresh.jpg", FileNaming.dedupe("fresh.jpg") { it in taken })
        assertEquals("noext (1)", FileNaming.dedupe("noext") { it == "noext" })
    }
}
