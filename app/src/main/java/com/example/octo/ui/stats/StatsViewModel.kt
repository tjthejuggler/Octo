package com.example.octo.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.octo.OctoApplication
import com.example.octo.data.local.BodyPart
import com.example.octo.data.local.BodyPartDao
import com.example.octo.data.local.RangeTotalRow
import com.example.octo.data.local.SessionDao
import com.example.octo.data.local.SessionRangeTotalRow
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** One row of the "which tentacles" frequency list (+ its sparkline series). */
data class PartStat(
    val part: BodyPart,
    val minutes: Int,
    val sessions: Int,
    /** Daily minutes across the window, zero-filled, downsampled to ≤30 points. */
    val series: List<Int>,
)

data class StatsUiState(
    val range: StatsRange = StatsRange.D30,
    val loading: Boolean = true,
    val totalMinutes: Int = 0,
    val totalSessions: Int = 0,
    val activeDays: Int = 0,
    val avgMinutesPerActiveDay: Int = 0,
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val buckets: List<TrendBucket> = emptyList(),
    val parts: List<PartStat> = emptyList(),
    /** Sanity cross-check from the raw sessions table ("logged X min across Y sessions"). */
    val sanityMinutes: Int = 0,
    val sanitySessions: Int = 0,
) {
    val isEmpty: Boolean get() = totalMinutes == 0 && totalSessions == 0
}

/** Bundle for the 5-arity combine nest (kotlinx combine is typed up to 5 flows). */
private data class RawStats(
    val totals: RangeTotalRow?,
    val partRows: List<com.example.octo.data.local.PartTotalRow>,
    val dailyRows: List<com.example.octo.data.local.DailyTotalRow>,
    val rawRows: List<com.example.octo.data.local.BodyPartStretchEntity>,
    val activeDates: List<String>,
)

/**
 * Stats tab (Phase 7): window-selectable aggregates over the daily
 * [com.example.octo.data.local.BodyPartStretchEntity] rows written on session finish,
 * with streaks from ~365 days of distinct active dates and a raw-sessions sanity total.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val bodyPartDao: BodyPartDao,
    private val sessionDao: SessionDao,
) : ViewModel() {

    private val _range = MutableStateFlow(StatsRange.D30)
    val range: StateFlow<StatsRange> = _range.asStateFlow()

    /** Last ~365 days of distinct active dates — cheap input for current+best streaks. */
    private val streakDates: Flow<List<String>> = let {
        val today = LocalDate.now()
        bodyPartDao.activeDates(today.minusDays(STREAK_LOOKBACK_DAYS - 1).iso(), today.iso())
    }

    val state: StateFlow<StatsUiState> = _range.flatMapLatest { r ->
        val today = LocalDate.now()
        val fromIso = r.fromDate(today)
        val toIso = today.iso()
        val zone = ZoneId.systemDefault()
        val fromMillis = when (r.days) {
            null -> 0L
            else -> LocalDate.parse(fromIso).atStartOfDay(zone).toInstant().toEpochMilli()
        }
        val toMillis = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

        combine(
            combine(
                bodyPartDao.rangeTotals(fromIso, toIso),
                bodyPartDao.partTotalsInRange(fromIso, toIso),
                bodyPartDao.dailyTotalsInRange(fromIso, toIso),
                bodyPartDao.forRange(fromIso, toIso), // per-part daily series for sparklines
                streakDates,
            ) { totals, partRows, dailyRows, rawRows, activeDates ->
                RawStats(totals, partRows, dailyRows, rawRows, activeDates)
            },
            sessionDao.rangeTotals(fromMillis, toMillis),
        ) { raw, sanity ->
            buildState(raw, r, today, sanity)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    fun selectRange(range: StatsRange) {
        _range.value = range
    }

    private fun buildState(
        raw: RawStats,
        range: StatsRange,
        today: LocalDate,
        sanity: SessionRangeTotalRow?,
    ): StatsUiState {
        val totals = raw.totals ?: RangeTotalRow(minutes = 0, sessions = 0, activeDays = 0)
        val minutesByDate = raw.dailyRows.associate { it.date to it.minutes }
        val firstDataDate = raw.dailyRows.firstOrNull()
            ?.let { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        val displayStart = range.displayStart(today, firstDataDate)
        val dailySeries: List<Pair<LocalDate, Int>> = generateSequence(displayStart) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { it to (minutesByDate[it.iso()] ?: 0) }
            .toList()

        val streaks = StatsMath.computeStreaks(raw.activeDates.toSet(), today)
        val buckets = StatsMath.bucketDaily(dailySeries, today, range)

        val partById = raw.partRows.associateBy { it.bodyPartId }
        val minutesByPartDate: Map<String, Map<String, Int>> = raw.rawRows
            .groupBy { it.bodyPartId }
            .mapValues { (_, rows) -> rows.associate { it.date to it.minutes } }
        val parts = BodyPart.entries
            .map { p ->
                val row = partById[p.id]
                val series = dailySeries.map { (d, _) -> minutesByPartDate[p.id]?.get(d.iso()) ?: 0 }
                PartStat(
                    part = p,
                    minutes = row?.minutes ?: 0,
                    sessions = row?.sessions ?: 0,
                    series = StatsMath.downsample(series),
                )
            }
            .sortedWith(
                compareByDescending<PartStat> { it.minutes }.thenByDescending { it.sessions },
            )

        return StatsUiState(
            range = range,
            loading = false,
            totalMinutes = totals.minutes,
            totalSessions = totals.sessions,
            activeDays = totals.activeDays,
            avgMinutesPerActiveDay = if (totals.activeDays > 0) totals.minutes / totals.activeDays else 0,
            currentStreak = streaks.current,
            bestStreak = streaks.best,
            buckets = buckets,
            parts = parts,
            sanityMinutes = (sanity?.totalSeconds ?: 0) / 60,
            sanitySessions = sanity?.sessions ?: 0,
        )
    }

    private fun LocalDate.iso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)

    companion object {
        private const val STREAK_LOOKBACK_DAYS = 365L

        /** Factory pattern consistent with the other screens (manual DI, no Hilt). */
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OctoApplication
                StatsViewModel(
                    bodyPartDao = app.appContainer.database.bodyPartDao(),
                    sessionDao = app.appContainer.database.sessionDao(),
                )
            }
        }
    }
}
