package com.storysaver.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrimRangeTest {

    @Test fun keepsAValidRange() {
        assertEquals(TrimRange(5_000, 20_000), TrimRange.normalize(5_000, 20_000, 60_000))
        assertEquals(15_000, TrimRange(5_000, 20_000).lengthMs)
    }

    @Test fun wholeFileMeansNoTrim() {
        assertNull(TrimRange.normalize(0, 60_000, 60_000))
        assertNull(TrimRange.normalize(100, 59_900, 60_000)) // within a quarter second of both ends
        assertEquals(TrimRange(0, 59_000), TrimRange.normalize(0, 59_000, 60_000)) // only the end moved
        assertEquals(TrimRange(1_000, 60_000), TrimRange.normalize(1_000, 60_000, 60_000)) // only the start moved
    }

    @Test fun clampsToTheFile() {
        assertEquals(TrimRange(0, 10_000), TrimRange.normalize(-500, 10_000, 60_000))
        assertEquals(TrimRange(30_000, 60_000), TrimRange.normalize(30_000, 90_000, 60_000))
    }

    @Test fun enforcesMinimumLength() {
        assertEquals(TrimRange(5_000, 6_000), TrimRange.normalize(5_000, 5_200, 60_000))
        assertEquals(TrimRange(5_000, 6_000), TrimRange.normalize(5_000, 4_000, 60_000)) // end before start
        // At the very end: the start moves back instead of running past the file.
        assertEquals(TrimRange(59_000, 60_000), TrimRange.normalize(59_800, 59_900, 60_000))
    }

    @Test fun unknownDurationKeepsTheRange() {
        assertEquals(TrimRange(0, 10_000), TrimRange.normalize(0, 10_000, 0))
        assertEquals(TrimRange(3_000, 4_000), TrimRange.normalize(3_000, 3_500, -1))
    }

    @Test fun fileLabels() {
        assertEquals("0m05s-0m20s", TrimRange(5_000, 20_900).fileLabel())
        assertEquals("1m05s-12m00s", TrimRange(65_000, 720_000).fileLabel())
        assertEquals("1h02m05s-1h02m30s", TrimRange(3_725_000, 3_750_000).fileLabel())
        assertFalse(TrimRange(65_000, 720_000).fileLabel().contains(':'))
    }

    @Test fun clockRoundTrips() {
        assertEquals("0:00.0", TrimRange.clock(0))
        assertEquals("1:05.3", TrimRange.clock(65_300))
        assertEquals("1:02:05.3", TrimRange.clock(3_725_300))
        assertEquals("0:00.0", TrimRange.clock(-20))
        for (ms in listOf(0L, 500L, 65_300L, 3_725_300L, 599_900L)) assertEquals(ms, TrimRange.parseClock(TrimRange.clock(ms)))
        assertNull(TrimRange.parseClock("abc"))
        assertNull(TrimRange.parseClock("12"))
    }

    @Test fun onlyMp4FamilyCanBeTrimmed() {
        assertTrue(TrimRange.canTrim("video/mp4"))
        assertTrue(TrimRange.canTrim("audio/mp4"))
        assertFalse(TrimRange.canTrim("video/webm"))
        assertFalse(TrimRange.canTrim("audio/mpeg"))
        assertFalse(TrimRange.canTrim("image/jpeg"))
        assertFalse(TrimRange.canTrim(null))
    }
}
