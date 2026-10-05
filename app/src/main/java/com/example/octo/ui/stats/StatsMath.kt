package com.example.octo.ui.stats

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Selected stats window. [days] == null means "all time" (open-ended SQL range).
 */
enum class StatsRange(val label: String, val days: Long?) {
    D7("7D", 7),
    D30("30D", 30),
    D90("90D", 90),
    ALL("ALL", null);

    /** Inclusive ISO start date of the window ending on [today] ("0000-01-01" for [ALL]). */
    fun fromDate(today: LocalDate): String = when (days) {
        null -> "0000-01-01" // lexicographic lower bound for SQLite BETWEEN on ISO dates
        else -> today.minusDays(days - 1).format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    /**
     * Concrete start date for charts/sparklines. Fixed ranges zero-fill from the window
     * start; [ALL] clamps to the first day that actually has data to skip dead months.
     */
    fun displayStart(today: LocalDate, firstDataDate: LocalDate?): LocalDate = when (days) {
        null -> firstDataDate ?: today
        else -> today.minusDays(days - 1)
    }
}

/** One bar of the trend chart (day/week/month depending on the window). */
data class TrendBucket(
    val label: String,
    val minutes: Int,
    val rollingAvg: Float,
    val containsToday: Boolean,
)

/** Current + best consecutive-active-day streaks. */
data class Streaks(val current: Int, val best: Int)

/**
 * Pure stats math for the stats screen — no Android or Compose dependencies so it
 * can be unit-tested on the JVM ([StatsMathTest]). [today] is always passed in
 * explicitly to keep tests deterministic.
 */
object StatsMath {

    private const val ROLLING_WINDOW = 7
    const val SPARKLINE_POINTS = 30

    /**
     * Streak = consecutive active days. [Streaks.current] tolerates "today not yet
     * stretched" (counts from yesterday instead) — same semantics as the dashboard
     * streak in BodyViewModel.
     */
    fun computeStreaks(activeDates: Set<String>, today: LocalDate): Streaks {
        if (activeDates.isEmpty()) return Streaks(current = 0, best = 0)
        val days = activeDates.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()

        var current = 0
        var cursor = if (today in days) today else today.minusDays(1)
        while (cursor in days) {
            current++
            cursor = cursor.minusDays(1)
        }

        var best = 0
        var run = 0
        var prev: LocalDate? = null
        for (d in days) {
            run = if (prev != null && prev.plusDays(1) == d) run + 1 else 1
            if (run > best) best = run
            prev = d
        }
        return Streaks(current = current, best = best)
    }

    /**
     * Buckets a zero-filled daily series ([(date, minutes)] ascending, no gaps) for the
     * trend chart: daily bars for ≤30-day windows, weekly chunks aligned to end today
     * for 90D (first chunk clamped to the window start), monthly for ALL. Each bucket
     * also carries the bucket-average of the underlying 7-day rolling mean, drawn as
     * the line overlay. Totals are preserved exactly (buckets partition the window).
     */
    fun bucketDaily(
        daily: List<Pair<LocalDate, Int>>,
        today: LocalDate,
        range: StatsRange,
    ): List<TrendBucket> {
        if (daily.isEmpty()) return emptyList()
        val byDate = daily.toMap()
        val start = daily.first().first
        val allDates = datesIn(start, today)
        val rolling = rollingAverage(allDates.map { byDate[it] ?: 0 }, ROLLING_WINDOW)
        val indexOf = allDates.withIndex().associate { (i, d) -> d to i }

        val ranges: List<Pair<LocalDate, LocalDate>> = when (range) {
            StatsRange.D7, StatsRange.D30 -> allDates.map { it to it }
            StatsRange.D90 -> {
                val out = mutableListOf<Pair<LocalDate, LocalDate>>()
                var end = today
                while (end >= start) {
                    val s = maxOf(end.minusDays(ROLLING_WINDOW - 1L), start)
                    out += s to end
                    end = s.minusDays(1)
                }
                out.reverse()
                out
            }
            StatsRange.ALL -> {
                val months = LinkedHashSet<YearMonth>()
                allDates.forEach { months += YearMonth.from(it) }
                months.map { m ->
                    maxOf(m.atDay(1), start) to minOf(m.atEndOfMonth(), today)
                }
            }
        }

        return ranges.map { (s, e) ->
            val idx = indexOf.entries.filter { (d, _) -> !d.isBefore(s) && !d.isAfter(e) }.map { it.value }
            val values = allDates.filter { !it.isBefore(s) && !it.isAfter(e) }.map { byDate[it] ?: 0 }
            TrendBucket(
                label = bucketLabel(s, today, range),
                minutes = values.sum(),
                rollingAvg = if (idx.isEmpty()) 0f else idx.map { rolling[it] }.average().toFloat(),
                containsToday = !today.isBefore(s) && !today.isAfter(e),
            )
        }
    }

    /**
     * Downsamples a series to at most [target] points (index bucketed, values summed).
     * Identity when the series is already short — so 7D/30D sparklines stay 1:1.
     */
    fun downsample(values: List<Int>, target: Int = SPARKLINE_POINTS): List<Int> {
        if (target <= 0 || values.size <= target) return values
        val out = IntArray(target)
        values.forEachIndexed { i, v -> out[i * target / values.size] += v }
        return out.toList()
    }

    /** Trailing [window]-point simple moving average (partial windows at the head). */
    fun rollingAverage(values: List<Int>, window: Int = ROLLING_WINDOW): List<Float> =
        List(values.size) { i ->
            values.subList(maxOf(0, i - window + 1), i + 1).average().toFloat()
        }

    private fun datesIn(start: LocalDate, end: LocalDate): List<LocalDate> =
        generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()

    private fun bucketLabel(bucketStart: LocalDate, today: LocalDate, range: StatsRange): String =
        when (range) {
            StatsRange.D7, StatsRange.D30 ->
                bucketStart.format(DateTimeFormatter.ofPattern("M/d"))
            StatsRange.D90 ->
                bucketStart.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH))
            StatsRange.ALL ->
                if (bucketStart.year == today.year) {
                    bucketStart.format(DateTimeFormatter.ofPattern("MMM", Locale.ENGLISH))
                } else {
                    bucketStart.format(DateTimeFormatter.ofPattern("MMM yy", Locale.ENGLISH))
                }
        }
}
