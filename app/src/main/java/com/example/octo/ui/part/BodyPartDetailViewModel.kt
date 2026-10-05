package com.example.octo.ui.part

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.octo.OctoApplication
import com.example.octo.data.local.BodyPart
import com.example.octo.data.local.BodyPartDao
import com.example.octo.data.local.DailyTotalRow
import com.example.octo.data.local.RangeTotalRow
import com.example.octo.data.local.VideoBodyPartDao
import com.example.octo.data.local.VideoEntity
import com.example.octo.ui.stats.StatsMath
import com.example.octo.ui.stats.StatsRange
import com.example.octo.ui.stats.TrendBucket
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One library video on the detail screen (video row + all its body-part tags). */
data class PartVideo(
    val video: VideoEntity,
    val bodyParts: List<BodyPart>,
)

/** One history row: a single day's minutes/sessions for THIS part. */
data class PartHistoryRow(val date: String, val minutes: Int, val sessions: Int)

data class PartDetailUiState(
    val part: BodyPart? = null,
    val range: StatsRange = StatsRange.D30,
    // All-time header totals
    val allTimeMinutes: Int = 0,
    val allTimeSessions: Int = 0,
    // Selected window
    val windowMinutes: Int = 0,
    val windowSessions: Int = 0,
    val windowActiveDays: Int = 0,
    val buckets: List<TrendBucket> = emptyList(),
    // Personal records
    val bestDayMinutes: Int = 0,
    val longestGapDays: Int = 0,
    val history: List<PartHistoryRow> = emptyList(),
    val videos: List<PartVideo> = emptyList(),
) {
    val hasHistory: Boolean get() = history.isNotEmpty()
}

/**
 * Phase 11 — per-body-part detail screen ("part/{bodyPartId}").
 *
 * All window math is client-side over the part's per-day rows (SQL returns the
 * full per-day series once; range chips just filter/slice it — same totals as
 * the Phase 7 stats screen, scoped to one part). Trend bucketing reuses
 * [StatsMath.bucketDaily] so 90D/ALL roll up weekly/monthly identically.
 */
class BodyPartDetailViewModel(
    partIdArg: String,
    bodyPartDao: BodyPartDao,
    videoBodyPartDao: VideoBodyPartDao,
) : ViewModel() {

    val part: BodyPart? = BodyPart.fromId(partIdArg)
    private val partId: String = part?.id.orEmpty()

    private val _range = MutableStateFlow(StatsRange.D30)
    val range: StateFlow<StatsRange> = _range.asStateFlow()

    private val allDailyRows: Flow<List<DailyTotalRow>> =
        bodyPartDao.forPartForRange(partId, "0000-01-01", "9999-12-31")

    private val allTimeTotals: Flow<RangeTotalRow?> = bodyPartDao.allTimeTotalsFor(partId)

    private val historyRows: Flow<List<PartHistoryRow>> =
        bodyPartDao.dailyRowsForPart(partId, HISTORY_LIMIT)
            .map { rows -> rows.map { PartHistoryRow(it.date, it.minutes, it.sessions) } }

    private val videoRows: Flow<List<PartVideo>> =
        videoBodyPartDao.getVideosForPart(partId).map { rows ->
            rows.map { row ->
                PartVideo(
                    video = row.video,
                    bodyParts = row.bodyParts
                        .mapNotNull { BodyPart.fromId(it.bodyPartId) }
                        .sortedBy { it.ordinal },
                )
            }
        }

    val state: StateFlow<PartDetailUiState> = combine(
        _range,
        allDailyRows,
        allTimeTotals,
        historyRows,
        videoRows,
    ) { r, daily, totals, history, videos ->
        buildState(r, daily, totals, history, videos)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PartDetailUiState(part = part))

    fun selectRange(range: StatsRange) {
        _range.value = range
    }

    private fun buildState(
        range: StatsRange,
        daily: List<DailyTotalRow>,
        totals: RangeTotalRow?,
        history: List<PartHistoryRow>,
        videos: List<PartVideo>,
    ): PartDetailUiState {
        val today = LocalDate.now()
        val fromIso = range.fromDate(today)
        val byDate = daily.associate { it.date to it.minutes }
        val windowRows = daily.filter { it.date >= fromIso }
        val firstDataDate = daily.firstOrNull()?.let { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        val displayStart = range.displayStart(today, firstDataDate)
        val dailySeries: List<Pair<LocalDate, Int>> = generateSequence(displayStart) { it.plusDays(1) }
            .takeWhile { !it.isAfter(today) }
            .map { it to (byDate[it.format(DateTimeFormatter.ISO_LOCAL_DATE)] ?: 0) }
            .toList()

        return PartDetailUiState(
            part = part,
            range = range,
            allTimeMinutes = totals?.minutes ?: 0,
            allTimeSessions = totals?.sessions ?: 0,
            windowMinutes = windowRows.sumOf { it.minutes },
            windowSessions = windowRows.sumOf { it.sessions },
            windowActiveDays = windowRows.size,
            buckets = StatsMath.bucketDaily(dailySeries, today, range),
            bestDayMinutes = daily.maxOfOrNull { it.minutes } ?: 0,
            longestGapDays = longestGapDays(daily.map { it.date }),
            history = history,
            videos = videos,
        )
    }

    /** Longest run of inactive days BETWEEN first and last active day (0 when <2 active days). */
    private fun longestGapDays(dates: List<String>): Int {
        val parsed = dates.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }.sorted()
        if (parsed.size < 2) return 0
        var longest = 0
        for (i in 1 until parsed.size) {
            val gap = parsed[i].toEpochDay() - parsed[i - 1].toEpochDay() - 1
            if (gap > longest) longest = gap.toInt()
        }
        return longest
    }

    companion object {
        /** History cap — SQL-side LIMIT (Phase 11 scope: "capped ~60 rows"). */
        private const val HISTORY_LIMIT = 60

        /**
         * Per-arg factory (the route arg is a plain parameter, not SavedStateHandle —
         * the lifecycle version here lacks CreationExtras.createSavedStateHandle()).
         * The NavHost composable remembers one factory per back-stack entry.
         */
        fun factory(partId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OctoApplication
                BodyPartDetailViewModel(
                    partIdArg = partId,
                    bodyPartDao = app.appContainer.database.bodyPartDao(),
                    videoBodyPartDao = app.appContainer.database.videoBodyPartDao(),
                )
            }
        }
    }
}
