package com.example.octo.ui.library

import com.example.octo.ui.body.BodyTierBlue
import com.example.octo.ui.body.BodyTierGreen
import com.example.octo.ui.body.BodyTierIdleRed
import com.example.octo.ui.body.BodyTierOrange
import com.example.octo.ui.body.BodyTierPink
import com.example.octo.ui.body.BodyTierWhite
import com.example.octo.ui.body.BodyTierYellow
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for the FB28 library-card recency buckets + age markers. */
class LibraryRecencyTest {

    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 17)

    /** Epoch millis for noon UTC [daysAgo] — noon avoids any date-edge drift. */
    private fun millis(daysAgo: Long): Long =
        today.minusDays(daysAgo).atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()

    private fun bucketAt(daysAgo: Long) = recencyBucket(millis(daysAgo), today, zone)

    private fun labelAt(daysAgo: Long) = ageMarkerLabel(millis(daysAgo), today, zone)

    // ── Buckets ──

    @Test
    fun `today maps to TODAY`() = assertEquals(RecencyBucket.TODAY, bucketAt(0))

    @Test
    fun `yesterday maps to YESTERDAY`() = assertEquals(RecencyBucket.YESTERDAY, bucketAt(1))

    @Test
    fun `two days ago maps to DAY_BEFORE`() = assertEquals(RecencyBucket.DAY_BEFORE, bucketAt(2))

    @Test
    fun `three to six days map to THIS_WEEK`() {
        assertEquals(RecencyBucket.THIS_WEEK, bucketAt(3))
        assertEquals(RecencyBucket.THIS_WEEK, bucketAt(6))
    }

    @Test
    fun `seven to thirteen days map to LAST_WEEK`() {
        assertEquals(RecencyBucket.LAST_WEEK, bucketAt(7))
        assertEquals(RecencyBucket.LAST_WEEK, bucketAt(13))
    }

    @Test
    fun `fourteen to twenty days map to TWO_WEEKS_AGO`() {
        assertEquals(RecencyBucket.TWO_WEEKS_AGO, bucketAt(14))
        assertEquals(RecencyBucket.TWO_WEEKS_AGO, bucketAt(20))
    }

    @Test
    fun `twentyone plus days map to OLDER`() {
        assertEquals(RecencyBucket.OLDER, bucketAt(21))
        assertEquals(RecencyBucket.OLDER, bucketAt(400))
    }

    @Test
    fun `never played maps to NEVER and red`() {
        assertEquals(RecencyBucket.NEVER, recencyBucket(null, today, zone))
        assertEquals(BodyTierIdleRed, RecencyBucket.NEVER.tierColor())
    }

    // ── Tier palette wiring ──

    @Test
    fun `buckets follow the tier progression`() {
        assertEquals(BodyTierWhite, RecencyBucket.TODAY.tierColor())
        assertEquals(BodyTierYellow, RecencyBucket.YESTERDAY.tierColor())
        assertEquals(BodyTierPink, RecencyBucket.DAY_BEFORE.tierColor())
        assertEquals(BodyTierBlue, RecencyBucket.THIS_WEEK.tierColor())
        assertEquals(BodyTierGreen, RecencyBucket.LAST_WEEK.tierColor())
        assertEquals(BodyTierOrange, RecencyBucket.TWO_WEEKS_AGO.tierColor())
        assertEquals(BodyTierIdleRed, RecencyBucket.OLDER.tierColor())
    }

    // ── Age markers ──

    @Test
    fun `marker labels scale d w mo y`() {
        assertEquals("today", labelAt(0))
        assertEquals("1d", labelAt(1))
        assertEquals("3d", labelAt(3))
        assertEquals("6d", labelAt(6))
        assertEquals("1w", labelAt(7))
        assertEquals("2w", labelAt(14))
        assertEquals("4w", labelAt(28))
        assertEquals("1mo", labelAt(30))
        assertEquals("12mo", labelAt(364))
        assertEquals("1y", labelAt(365))
        assertEquals("3y", labelAt(1096))
    }

    @Test
    fun `never played has no marker`() {
        assertNull(ageMarkerLabel(null, today, zone))
        assertNull(ageMarkerLabel(0L, today, zone))
    }
}
