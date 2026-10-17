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
import com.example.octo.data.local.VideoBodyPartDao
import com.example.octo.data.local.VideoMuscleDao
import com.example.octo.data.local.Muscle
import com.example.octo.data.local.MuscleMinutesRow
import com.example.octo.data.local.MuscleVideoCountRow
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

/** One row of the library coverage list: how many videos cover each body part. */
data class LibraryPartStat(
    val part: BodyPart,
    val videoCount: Int,
)

/** One row of the library coverage list in muscle mode: videos per muscle. */
data class LibraryMuscleStat(
    val muscle: Muscle,
    val videoCount: Int,
)

/** One row of the frequency list in muscle mode: confirmed-session minutes. */
data class MuscleStat(
    val muscle: Muscle,
    val minutes: Int,
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
    /**
     * Window-scaled minutes per color tier on the frequency bars — 10 for 7D,
     * 40 for 30D, 120 for 90D, span-derived for ALL. Drives bar LENGTH and
     * COLOR ([StatsMath.usageFraction]); recomputed on every range switch.
     */
    val tierStepMinutes: Int = StatsMath.TIER_STEP_MINUTES_PER_WEEK,
    val parts: List<PartStat> = emptyList(),
    /** Library coverage per body part — independent of the selected range. */
    val libraryParts: List<LibraryPartStat> = emptyList(),
    /** Library coverage per muscle — independent of the selected range. */
    val libraryMuscles: List<LibraryMuscleStat> = emptyList(),
    /** Confirmed-session minutes per muscle in the window — muscle-mode frequency list. */
    val muscleStats: List<MuscleStat> = emptyList(),
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
    videoBodyPartDao: VideoBodyPartDao,
    videoMuscleDao: VideoMuscleDao,
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
            videoMuscleDao.muscleMinutesInRange(fromMillis, toMillis),
            combine(
                videoBodyPartDao.videoCountsByPart(),
                videoMuscleDao.videoCountsByMuscle(),
            ) { partRows, muscleRows -> partRows to muscleRows },
        ) { raw, sanity, muscleRows, libraryRows ->
            buildState(raw, r, today, sanity, libraryRows.first, libraryRows.second, muscleRows)
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
        libraryRows: List<com.example.octo.data.local.PartVideoCountRow>,
        libraryMuscleRows: List<MuscleVideoCountRow>,
        muscleMinuteRows: List<MuscleMinutesRow>,
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

        // Span of the data window in days — feeds the ALL-time tier scale.
        val spanDays = dailySeries.size
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
            tierStepMinutes = StatsMath.tierStepMinutes(range, spanDays),
            parts = parts,
            sanityMinutes = (sanity?.totalSeconds ?: 0) / 60,
            sanitySessions = sanity?.sessions ?: 0,
            libraryParts = libraryStats(libraryRows),
            libraryMuscles = libraryMuscleStats(libraryMuscleRows),
            muscleStats = muscleStats(muscleMinuteRows),
        )
    }

    /** All 16 parts, scarcest first — the gaps you should go hunt videos for. */
    private fun libraryStats(rows: List<com.example.octo.data.local.PartVideoCountRow>): List<LibraryPartStat> {
        val countById = rows.associate { it.bodyPartId to it.videoCount }
        return BodyPart.entries
            .map { p -> LibraryPartStat(p, countById[p.id] ?: 0) }
            .sortedWith(compareBy<LibraryPartStat> { it.videoCount }.thenBy { it.part.displayName })
    }

    /** All muscles, scarcest first — fine-grained gaps beneath the 16 categories. */
    private fun libraryMuscleStats(rows: List<MuscleVideoCountRow>): List<LibraryMuscleStat> {
        val countById = rows.associate { it.muscleId to it.videoCount }
        return Muscle.entries
            .map { m -> LibraryMuscleStat(m, countById[m.id] ?: 0) }
            .sortedWith(compareBy<LibraryMuscleStat> { it.videoCount }.thenBy { it.muscle.displayName })
    }

    /** All muscles, most minutes first — muscle mode of the frequency list. */
    private fun muscleStats(rows: List<MuscleMinutesRow>): List<MuscleStat> {
        val minutesById = rows.associate { it.muscleId to it.minutes }
        return Muscle.entries
            .map { m -> MuscleStat(m, minutesById[m.id] ?: 0) }
            .sortedWith(compareByDescending<MuscleStat> { it.minutes }.thenBy { it.muscle.displayName })
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
                    videoBodyPartDao = app.appContainer.database.videoBodyPartDao(),
                    videoMuscleDao = app.appContainer.database.videoMuscleDao(),
                )
            }
        }
    }
}
