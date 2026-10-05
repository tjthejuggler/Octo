package com.example.octo.ui.stats

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.octo.R
import com.example.octo.data.local.BodyPart
import com.example.octo.ui.body.OctoBodyGreen
import com.example.octo.ui.body.OctoBodyTeal
import com.example.octo.ui.body.octoRegionColor

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatsHeader()
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
                    parts = state.parts,
                    windowLabel = state.range.label,
                    expandedPart = expandedPart,
                    onToggle = { p -> expandedPart = if (expandedPart == p) null else p },
                )
                Spacer(Modifier.height(12.dp))
                SanityNote(
                    minutes = state.sanityMinutes,
                    sessions = state.sanitySessions,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatsHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.octo_mascot),
            contentDescription = null, // decorative
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Stats", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "how the octopus is unwinding",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
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
 * Canvas bar chart — one bar per [TrendBucket]: daily for 7D/30D, weekly for 90D,
 * monthly for ALL. Today's bucket is green; the purple overlay is the 7-day rolling
 * average (bucket-averaged for weekly/monthly buckets).
 */
@Composable
private fun TrendCard(buckets: List<TrendBucket>, range: StatsRange) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Minutes over time", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                when (range) {
                    StatsRange.D7, StatsRange.D30 -> "per day · line = 7-day average"
                    StatsRange.D90 -> "per week · line = average of daily 7-day means"
                    StatsRange.ALL -> "per month · line = average of daily 7-day means"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TrendChart(buckets = buckets, modifier = Modifier.fillMaxWidth().height(150.dp))
            Spacer(Modifier.height(4.dp))
            BucketLabels(buckets)
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

@Composable
private fun BucketLabels(buckets: List<TrendBucket>) {
    val showEvery = ((buckets.size + 5) / 6).coerceAtLeast(1) // ~6 labels max
    Row(Modifier.fillMaxWidth()) {
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
 * All 16 parts: minutes + sessions in the window, bar length scaled to the max;
 * teal/green when ≥1 minute (dashboard region colors), slate when 0. Tap a row to
 * expand its 30-point sparkline.
 */
@Composable
private fun FrequencyList(
    parts: List<PartStat>,
    windowLabel: String,
    expandedPart: BodyPart?,
    onToggle: (BodyPart) -> Unit,
) {
    val neglected = parts.filter { it.minutes == 0 }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "Which tentacles are stretching",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        val maxMinutes = parts.maxOfOrNull { it.minutes } ?: 0
        parts.forEach { p ->
            PartRow(
                stat = p,
                fraction = if (maxMinutes == 0) 0f else p.minutes.toFloat() / maxMinutes,
                expanded = expandedPart == p.part,
                onToggle = { onToggle(p.part) },
            )
        }
        NeglectedCallout(neglected = neglected, windowLabel = windowLabel)
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
                            .background(octoRegionColor(stat.minutes)),
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
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
}

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
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
        ),
    ) {
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
