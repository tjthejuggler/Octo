package com.example.octo.ui.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the stats streak/bucketing/downsample helpers (Phase 7). */
class StatsMathTest {

    private val today: LocalDate = LocalDate.of(2026, 3, 15)

    private fun iso(d: LocalDate): String = d.toString()

    private fun daysBack(vararg offsets: Long): Set<String> =
        offsets.map { iso(today.minusDays(it)) }.toSet()

    // ── Streaks ──

    @Test
    fun `streaks empty when no active dates`() {
        assertEquals(Streaks(0, 0), StatsMath.computeStreaks(emptySet(), today))
    }

    @Test
    fun `current streak counts chain ending today`() {
        val s = StatsMath.computeStreaks(daysBack(0, 1, 2), today)
        assertEquals(3, s.current)
        assertEquals(3, s.best)
    }

    @Test
    fun `current streak tolerates missing today`() {
        val s = StatsMath.computeStreaks(daysBack(1, 2, 3), today)
        assertEquals(3, s.current)
    }

    @Test
    fun `current streak zero when chain broken before today`() {
        val s = StatsMath.computeStreaks(daysBack(2), today)
        assertEquals(0, s.current)
    }

    @Test
    fun `best streak can exceed current`() {
        val s = StatsMath.computeStreaks(daysBack(0, 1, 4, 5, 6), today)
        assertEquals(2, s.current)
        assertEquals(3, s.best)
    }

    @Test
    fun `best streak ignores malformed dates`() {
        val s = StatsMath.computeStreaks(setOf("2026-03-15", "garbage", "2026-03-14"), today)
        assertEquals(2, s.current)
        assertEquals(2, s.best)
    }

    // ── Downsample ──

    @Test
    fun `downsample identity for short series`() {
        val v = List(30) { it }
        assertEquals(v, StatsMath.downsample(v))
    }

    @Test
    fun `downsample preserves total`() {
        val v = List(90) { 1 }
        val out = StatsMath.downsample(v, target = 30)
        assertEquals(30, out.size)
        assertEquals(v.sum(), out.sum())
        assertEquals(3, out[0])
    }

    // ── Rolling average ──

    @Test
    fun `rolling average is trailing window mean`() {
        val out = StatsMath.rollingAverage(listOf(7, 7, 0, 0, 0, 0, 0), window = 7)
        assertEquals(listOf(7f, 7f, 14f / 3f, 3.5f, 2.8f, 14f / 6f, 2f), out)
    }

    // ── Bucketing ──

    private fun dailySeries(days: Int, minutesOf: (Int) -> Int): List<Pair<LocalDate, Int>> =
        (0 until days).map { i -> today.minusDays(days - 1 - i.toLong()) to minutesOf(i) }

    @Test
    fun `daily buckets for 7D`() {
        val series = dailySeries(7) { it + 1 }
        val buckets = StatsMath.bucketDaily(series, today, StatsRange.D7)
        assertEquals(7, buckets.size)
        assertEquals(series.sumOf { it.second }, buckets.sumOf { it.minutes })
        assertTrue(buckets.last().containsToday)
        assertFalse(buckets.first().containsToday)
    }

    @Test
    fun `daily buckets for 30D`() {
        val series = dailySeries(30) { if (it % 5 == 0) 10 else 0 }
        val buckets = StatsMath.bucketDaily(series, today, StatsRange.D30)
        assertEquals(30, buckets.size)
        assertEquals(60, buckets.sumOf { it.minutes })
    }

    @Test
    fun `weekly buckets for 90D preserve totals`() {
        val series = dailySeries(90) { it % 9 }
        val buckets = StatsMath.bucketDaily(series, today, StatsRange.D90)
        assertEquals(13, buckets.size) // 12 × 7-day weeks + clamped 6-day head week
        assertEquals(series.sumOf { it.second }, buckets.sumOf { it.minutes })
        // Every bucket except the head covers ≤7 days; today only in the last.
        assertTrue(buckets.last().containsToday)
        assertTrue(buckets.dropLast(1).none { it.containsToday })
    }

    @Test
    fun `monthly buckets for ALL clamp to data range`() {
        val start = LocalDate.of(2025, 12, 10)
        val allDates = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
        val series = allDates.map { it to (it.dayOfMonth % 4) }
        val buckets = StatsMath.bucketDaily(series, today, StatsRange.ALL)
        assertEquals(4, buckets.size) // Dec 2025 (clamped) + Jan + Feb + Mar 2026 (clamped)
        assertEquals(series.sumOf { it.second }, buckets.sumOf { it.minutes })
        assertTrue(buckets.last().containsToday)
        // Dec 2025 is a previous year → year-qualified label; Mar 2026 is the current year.
        assertEquals("Dec 25", buckets.first().label)
        assertEquals("Mar", buckets.last().label)
    }

    @Test
    fun `ALL across a year uses year-qualified labels`() {
        val start = LocalDate.of(2025, 3, 20)
        val allDates = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.toList()
        val series = allDates.map { it to 1 }
        val buckets = StatsMath.bucketDaily(series, today, StatsRange.ALL)
        assertEquals("Mar 25", buckets.first().label)
        assertEquals("Mar", buckets.last().label)
    }

    @Test
    fun `bucketing an empty series is empty`() {
        assertTrue(StatsMath.bucketDaily(emptyList(), today, StatsRange.D30).isEmpty())
    }
}
