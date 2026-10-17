package com.example.octo.ui.stats

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.octo.R
import com.example.octo.data.local.BodyPart
import com.example.octo.ui.body.OctoBodyGreen
import com.example.octo.ui.components.OctoHeader
import com.example.octo.ui.body.OctoBodyTeal
import com.example.octo.ui.body.usageTierColor
import com.example.octo.ui.components.GlassCard
import java.util.Locale

/**
 * Stats tab (Phase 7): range-selectable overview cards, per-body-part frequency bars
 * with expandable sparklines, a Canvas daily/weekly/monthly trend chart, and an
 * octopus-themed empty state. Visual language matches the dashboard
 * ([com.example.octo.ui.body.BodyScreen]): teal→green stretched colors, slate idle,
 * soft cards, mascot.
 */
@Composable
fun StatsScreen(
    modifier: Modifier = Modifier,
    viewModel: StatsViewModel = viewModel(factory = StatsViewModel.Factory),
) {
    val state by viewModel.state.collectAsState()
    var expandedPart by remember { mutableStateOf<BodyPart?>(null) }

    // ONE shared Parts/Muscles toggle — flipping it switches BOTH the frequency
    // list and the library coverage card at once.
    var muscleMode by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OctoHeader(title = "Stats", subtitle = "how the octopus is unwinding")
        RangeChips(
            selected = state.range,
            onSelect = viewModel::selectRange,
            modifier = Modifier.padding(vertical = 8.dp),
        )

        when {
            state.loading && state.isEmpty -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            state.isEmpty -> EmptyStatsCard(modifier = Modifier.padding(vertical = 24.dp))

            else -> {
                OverviewCards(state = state)
                Spacer(Modifier.height(12.dp))
                TrendCard(buckets = state.buckets, range = state.range)
                Spacer(Modifier.height(12.dp))
                FrequencyList(
                    state = state,
                    windowLabel = state.range.label,
                    expandedPart = expandedPart,
                    onToggle = { p -> expandedPart = if (expandedPart == p) null else p },
                    muscleMode = muscleMode,
                    onMuscleModeChange = { muscleMode = it },
                )
                Spacer(Modifier.height(12.dp))
                SanityNote(
                    minutes = state.sanityMinutes,
                    sessions = state.sanitySessions,
                )
            }
        }

        // Library coverage is independent of the session window — always visible.
        Spacer(Modifier.height(12.dp))
        LibraryCoverageCard(
            parts = state.libraryParts,
            muscles = state.libraryMuscles,
            loading = state.loading,
            muscleMode = muscleMode,
            onMuscleModeChange = { muscleMode = it },
        )
        Spacer(Modifier.height(24.dp))
    }
}

/** 7D / 30D / 90D / ALL window selector (defaults 30D). */
@Composable
private fun RangeChips(
    selected: StatsRange,
    onSelect: (StatsRange) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatsRange.entries.forEach { r ->
            FilterChip(
                selected = r == selected,
                onClick = { onSelect(r) },
                label = { Text(r.label) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun OverviewCards(state: StatsUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("${state.totalMinutes}", "total min", Modifier.weight(1f))
            StatCard("${state.totalSessions}", "sessions", Modifier.weight(1f))
            StatCard("${state.activeDays}", "active days", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("${state.avgMinutesPerActiveDay}", "avg min/day", Modifier.weight(1f))
            StatCard(
                value = "🔥 ${state.currentStreak}",
                label = "current streak",
                modifier = Modifier.weight(1f),
                dimmed = state.currentStreak == 0,
            )
            StatCard(
                value = "🏆 ${state.bestStreak}",
                label = "best streak",
                modifier = Modifier.weight(1f),
                dimmed = state.bestStreak == 0,
            )
        }
    }
}

@Composable
private fun StatCard(value: String, label: String, modifier: Modifier = Modifier, dimmed: Boolean = false) {
    GlassCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * "Minutes over time" card — tappable to open the [TrendChartDialog] landscape
 * full-screen view. The inline card shows a y-axis (minute values up the left)
 * and compact x labels (bare day numbers for 7D/30D, ~6 shown for 30D; week
 * dates for 90D; month names for ALL).
 */
@Composable
private fun TrendCard(buckets: List<TrendBucket>, range: StatsRange) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .padding(12.dp)
                .clickable { expanded = true },
        ) {
            Text("Minutes over time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when (range) {
                    StatsRange.D7, StatsRange.D30 -> "per day · line = 7-day average · tap to expand"
                    StatsRange.D90 -> "per week · line = average of daily 7-day means · tap to expand"
                    StatsRange.ALL -> "per month · line = average of daily 7-day means · tap to expand"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                YAxisLabels(buckets = buckets, modifier = Modifier.height(150.dp).width(30.dp))
                TrendChart(buckets = buckets, modifier = Modifier.fillMaxWidth().height(150.dp))
            }
            Spacer(Modifier.height(4.dp))
            Row {
                Spacer(Modifier.width(30.dp))
                BucketLabels(buckets, maxLabels = 6, modifier = Modifier.weight(1f))
            }
        }
    }
    if (expanded) {
        TrendChartDialog(buckets = buckets, range = range, onDismiss = { expanded = false })
    }
}

/**
 * Landscape full-screen chart: every bucket gets an x label (day numbers for
 * 7D/30D, month names for ALL) with a bigger canvas and the same y-axis.
 */
@Composable
private fun TrendChartDialog(
    buckets: List<TrendBucket>,
    range: StatsRange,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val prev = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            if (prev != null) activity.requestedOrientation = prev
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onDismiss)
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Minutes over time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when (range) {
                    StatsRange.D7, StatsRange.D30 -> "per day · line = 7-day average · tap anywhere to close"
                    StatsRange.D90 -> "per week · line = average of daily 7-day means · tap anywhere to close"
                    StatsRange.ALL -> "per month · line = average of daily 7-day means · tap anywhere to close"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                YAxisLabels(buckets = buckets, modifier = Modifier.height(260.dp).width(34.dp))
                TrendChart(buckets = buckets, modifier = Modifier.fillMaxWidth().height(260.dp))
            }
            Spacer(Modifier.height(4.dp))
            // Landscape has room: show EVERY bucket label (day numbers / month names).
            Row {
                Spacer(Modifier.width(34.dp))
                BucketLabels(buckets, maxLabels = buckets.size, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Locks the host activity to landscape while this chart composable is up. */
private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * Minute values up the left side of the chart — 4 grid-aligned ticks
 * (max, ~2/3, ~1/3, 0) with a unit suffix on the top tick.
 */
@Composable
private fun YAxisLabels(buckets: List<TrendBucket>, modifier: Modifier = Modifier) {
    val maxVal = buckets.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
    val ticks = listOf(maxVal, maxVal * 2 / 3, maxVal / 3, 0)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        ticks.forEachIndexed { i, t ->
            Text(
                text = if (i == 0) "$t m" else "$t",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun TrendChart(buckets: List<TrendBucket>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.secondary
    val baseline = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier) {
        if (buckets.isEmpty()) return@Canvas
        val maxVal = buckets.maxOf { it.minutes }.coerceAtLeast(1)
        val step = size.width / buckets.size
        val barWidth = step * 0.62f
        val chartH = size.height - 4.dp.toPx()

        // Baseline
        drawLine(baseline, Offset(0f, size.height), Offset(size.width, size.height), 2.dp.toPx())

        buckets.forEachIndexed { i, b ->
            val h = (b.minutes.toFloat() / maxVal) * chartH
            val x = i * step + (step - barWidth) / 2
            val color = if (b.containsToday) OctoBodyGreen else OctoBodyTeal
            drawRoundRect(
                color = if (b.minutes > 0) color else baseline.copy(alpha = 0.5f),
                topLeft = Offset(x, size.height - h),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = CornerRadius(3.dp.toPx()),
            )
        }

        // 7-day rolling average overlay
        if (buckets.any { it.rollingAvg > 0f }) {
            val path = Path()
            buckets.forEachIndexed { i, b ->
                val cx = i * step + step / 2
                val cy = size.height - (b.rollingAvg / maxVal) * chartH
                if (i == 0) path.moveTo(cx, cy) else path.lineTo(cx, cy)
            }
            drawPath(path, lineColor, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

/**
 * X-axis labels under the chart. Portrait shows at most [maxLabels] evenly
 * spaced ones (30 daily buckets are too cramped for all); the landscape dialog
 * passes every bucket so each day/month gets its own number.
 */
@Composable
private fun BucketLabels(
    buckets: List<TrendBucket>,
    maxLabels: Int,
    modifier: Modifier = Modifier,
) {
    val showEvery = if (buckets.size <= maxLabels) 1 else ((buckets.size + maxLabels - 1) / maxLabels)
    Row(modifier = modifier.fillMaxWidth()) {
        buckets.forEachIndexed { i, b ->
            Text(
                text = if (i % showEvery == 0 || i == buckets.lastIndex) b.label else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * All 16 parts: minutes + sessions in the window. Bar LENGTH and COLOR both use
 * the window's ABSOLUTE minute scale ([state.tierStepMinutes]): 10 min per tier
 * step in the week view (orange→green at 10), 40 in the month view (orange→green
 * at 40, green→blue at 80), 120 in 90D, span-derived for ALL. A full white bar
 * = 6 tier steps; bars no longer race to the top just for being the window's
 * busiest part. Tap a row to expand its 30-point sparkline.
 */
/** The shared Parts/Muscles toggle chip pair used by both stats sections. */
@Composable
private fun MuscleModeToggle(muscleMode: Boolean, onMuscleModeChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(
            selected = !muscleMode,
            onClick = { onMuscleModeChange(false) },
            label = { Text("Parts") },
        )
        FilterChip(
            selected = muscleMode,
            onClick = { onMuscleModeChange(true) },
            label = { Text("Muscles") },
        )
    }
}

@Composable
private fun FrequencyList(
    state: StatsUiState,
    windowLabel: String,
    expandedPart: BodyPart?,
    onToggle: (BodyPart) -> Unit,
    muscleMode: Boolean,
    onMuscleModeChange: (Boolean) -> Unit,
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Tentacle Usage",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            MuscleModeToggle(muscleMode = muscleMode, onMuscleModeChange = onMuscleModeChange)
        }
        Text(
            "minutes per ${if (muscleMode) "muscle" else "body part"} · most first",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (muscleMode) {
            // Muscle mode: confirmed-session minutes per muscle on the SAME
            // window-scaled tier yardstick as the body-part rows below.
            state.muscleStats.forEach { m ->
                MuscleFrequencyRow(
                    stat = m,
                    fraction = StatsMath.usageFraction(m.minutes, state.tierStepMinutes),
                )
            }
        } else {
            val parts = state.parts
            parts.forEach { p ->
                PartRow(
                    stat = p,
                    fraction = StatsMath.usageFraction(p.minutes, state.tierStepMinutes),
                    expanded = expandedPart == p.part,
                    onToggle = { onToggle(p.part) },
                )
            }
            NeglectedCallout(neglected = parts.filter { it.minutes == 0 }, windowLabel = windowLabel)
        }
        }
    }
}

/** Muscle-mode frequency row: minutes in the window on the same tier scale as parts. */
@Composable
private fun MuscleFrequencyRow(stat: MuscleStat, fraction: Float) {
    val idleColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stat.muscle.displayName,
            style = MaterialTheme.typography.labelMedium,
            color = if (stat.minutes > 0) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(118.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(idleColor),
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(usageTierColor(fraction)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${stat.minutes}m",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(58.dp),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun PartRow(
    stat: PartStat,
    fraction: Float,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val idleColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stat.part.displayName,
                style = MaterialTheme.typography.labelMedium,
                color = if (stat.minutes > 0) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(118.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(idleColor),
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(usageTierColor(fraction)),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${stat.minutes}m · ${stat.sessions}s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(58.dp),
                textAlign = TextAlign.End,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(top = 6.dp, start = 118.dp, end = 66.dp)) {
                Sparkline(
                    series = stat.series,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                )
                Text(
                    "minutes per day in window (≤30 points)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Tiny Canvas sparkline: teal line + soft area fill over the (downsampled) series. */
@Composable
private fun Sparkline(series: List<Int>, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier) {
        if (series.isEmpty()) return@Canvas
        val maxVal = series.max().coerceAtLeast(1)
        val step = if (series.size > 1) size.width / (series.size - 1) else size.width
        val chartH = size.height - 4.dp.toPx()

        fun y(v: Int) = size.height - (v.toFloat() / maxVal) * chartH

        val line = Path()
        series.forEachIndexed { i, v ->
            val pt = Offset(i * step, y(v))
            if (i == 0) line.moveTo(pt.x, pt.y) else line.lineTo(pt.x, pt.y)
        }
        val area = Path().apply {
            addPath(line)
            lineTo((series.size - 1) * step, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            area,
            brush = Brush.verticalGradient(listOf(OctoBodyTeal.copy(alpha = 0.35f), Color.Transparent)),
        )
        drawPath(
            line,
            color = if (series.any { it > 0 }) OctoBodyTeal else dim,
            style = Stroke(width = 2.dp.toPx()),
        )
    }
}

/** Playful callout for parts with 0 minutes in the window — the stiff tentacles. */
@Composable
private fun NeglectedCallout(neglected: List<PartStat>, windowLabel: String) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            if (neglected.isEmpty()) {
                "🐙 Every tentacle got some love — nothing stiff in this window!"
            } else {
                "🦑 Stiff tentacles (${neglected.size})"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (neglected.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                "${neglected.joinToString { it.part.displayName }} — the octopus hasn't " +
                    "stretched these in the last $windowLabel. Loosen them up, one arm at a time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Library coverage: how many videos in the library cover each body part (or,
 * toggled, each muscle), scarcest first — the top of the list is your shopping
 * list for new videos.
 *
 * Bars are RELATIVE to the current list: whichever part/muscle has the most
 * videos is a full white bar, none is red, the least-but-some is orange, and
 * everything else interpolates through the tier ramp in between. Bar length
 * likewise scales to the list's maximum count.
 */
@Composable
private fun LibraryCoverageCard(
    parts: List<LibraryPartStat>,
    muscles: List<LibraryMuscleStat>,
    loading: Boolean,
    muscleMode: Boolean,
    onMuscleModeChange: (Boolean) -> Unit,
) {
    val counts = if (muscleMode) muscles.map { it.videoCount } else parts.map { it.videoCount }
    val maxCount = counts.maxOrNull() ?: 0
    val minNonZero = counts.filter { it > 0 }.minOrNull()
    val missingNames = if (muscleMode) {
        muscles.filter { it.videoCount == 0 }.map { it.muscle.displayName }
    } else {
        parts.filter { it.videoCount == 0 }.map { it.part.displayName }
    }
    val total = counts.sum()
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Library coverage", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                MuscleModeToggle(muscleMode = muscleMode, onMuscleModeChange = onMuscleModeChange)
            }
            Text(
                if (muscleMode) "videos per muscle · scarcest first" else "videos per body part · scarcest first",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (loading && counts.isEmpty()) {
                Text(
                    "counting tentacles… 🐙",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (muscleMode) {
                    muscles.forEach { m ->
                        LibraryBarRow(
                            name = m.muscle.displayName,
                            videoCount = m.videoCount,
                            maxCount = maxCount,
                            minNonZero = minNonZero,
                        )
                    }
                } else {
                    parts.forEach { p ->
                        LibraryBarRow(
                            name = p.part.displayName,
                            videoCount = p.videoCount,
                            maxCount = maxCount,
                            minNonZero = minNonZero,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (missingNames.isEmpty()) {
                        "🐙 Every ${if (muscleMode) "muscle" else "body part"} has at least one video — well-stocked larder!"
                    } else {
                        "🦑 No videos yet for: ${missingNames.joinToString()} — go hunt some down!"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (total > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    "$total tags across ${counts.count { it > 0 }} of ${counts.size} ${if (muscleMode) "muscles" else "parts"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * One coverage bar. Length = count / list max (the busiest row fills the track).
 * Color rides the [usageTierColor] ramp over a RELATIVE 0..1 spread: 0 → red,
 * least-nonzero → orange-ish, list max → white.
 */
@Composable
private fun LibraryBarRow(
    name: String,
    videoCount: Int,
    maxCount: Int,
    minNonZero: Int?,
) {
    val idleColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val lengthFraction = if (maxCount == 0) 0f else videoCount.toFloat() / maxCount
    val colorFraction = when {
        videoCount <= 0 -> 0f // red
        maxCount == minNonZero -> 1f // every nonzero row ties for the top → all white
        else -> 0.08f + 0.92f * (videoCount - (minNonZero ?: 1)).toFloat() / (maxCount - (minNonZero ?: 1))
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = if (videoCount > 0) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(118.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(idleColor),
        ) {
            if (lengthFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(lengthFraction)
                        .fillMaxHeight()
                        .background(usageTierColor(colorFraction)),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = plural(videoCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(58.dp),
            textAlign = TextAlign.End,
        )
    }
}

private fun plural(count: Int): String = if (count == 1) "1 video" else "$count videos"

/** Phase 4 raw-session sanity line (not a reconciliation — just a cross-check). */
@Composable
private fun SanityNote(minutes: Int, sessions: Int) {
    Text(
        "Cross-check: raw session log says $minutes min across $sessions sessions 🐙",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun EmptyStatsCard(modifier: Modifier = Modifier) {
    GlassCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter = painterResource(R.drawable.octo_mascot),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "No stats yet — finish a session and your stats will swim in 🐙",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
