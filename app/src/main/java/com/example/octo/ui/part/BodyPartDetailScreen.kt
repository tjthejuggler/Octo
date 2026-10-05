package com.example.octo.ui.part

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.octo.R
import com.example.octo.data.local.BodyPart
import com.example.octo.data.local.DownloadState
import com.example.octo.data.local.VideoEntity
import com.example.octo.ui.body.OctoBodyGreen
import com.example.octo.ui.body.OctoBodyTeal
import com.example.octo.ui.body.octoRegionColor
import com.example.octo.ui.stats.StatsRange
import com.example.octo.ui.stats.TrendBucket
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Phase 11 — body-part detail ("part/{bodyPartId}"): header with all-time totals,
 * the videos tagged with this part (compact Library-style cards), window-chip
 * stats with a daily-minutes trend chart, personal records, and a capped
 * chronological history list.
 *
 * Video taps navigate to the Phase 4 player route for now — Phase 12's
 * "video/{videoId}" detail screen will become the target.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyPartDetailScreen(
    partId: String,
    onNavigateBack: () -> Unit,
    onOpenVideo: (String) -> Unit,
) {
    // One factory per composition keyed by the route arg (per back-stack entry).
    val viewModel: BodyPartDetailViewModel = viewModel(
        key = "part_detail_$partId",
        factory = remember(partId) { BodyPartDetailViewModel.factory(partId) },
    )
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.part?.displayName ?: stringResource(R.string.part_detail_title_fallback)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.part_detail_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (state.part == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.part_detail_unknown),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { HeaderCard(state) }
            item { SectionLabel(stringResource(R.string.part_detail_videos_section, state.part!!.displayName)) }
            if (state.videos.isEmpty()) {
                item {
                    EmptyHint(stringResource(R.string.part_detail_videos_empty))
                }
            } else {
                items(state.videos, key = { it.video.id }) { item ->
                    PartVideoCard(item = item, onClick = { onOpenVideo(item.video.id) })
                }
            }
            item { SectionLabel(stringResource(R.string.part_detail_stats_section)) }
            item { StatsSection(state = state, onRangeSelected = viewModel::selectRange) }
            item { SectionLabel(stringResource(R.string.part_detail_history_section)) }
            if (!state.hasHistory) {
                item { EmptyHint(stringResource(R.string.part_detail_history_empty)) }
            } else {
                items(state.history, key = { it.date }) { row ->
                    HistoryRow(row = row, accent = octoRegionColor(row.minutes))
                }
            }
        }
    }
}

/**
 * Phase 16 polish: the TopAppBar already shows the part name, so the header is
 * now ONE compact row (accent dot + all-time totals) instead of a tall card
 * duplicating the title in large text — the list content starts higher.
 */
@Composable
private fun HeaderCard(state: PartDetailUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Colored accent dot — same teal→green scale as the dashboard regions.
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(octoRegionColor(state.allTimeMinutes)),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(
                R.string.part_detail_all_time,
                state.allTimeMinutes,
                state.allTimeSessions,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Compact Library-style card: thumbnail, title, channel, duration, state badge, part chips. */
@Composable
private fun PartVideoCard(item: PartVideo, onClick: () -> Unit) {
    val video = item.video
    Card(modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = thumbnailModel(video),
                contentDescription = video.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .width(120.dp)
                    .height(68.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (video.channel.isNotBlank()) {
                    Text(
                        text = video.channel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (video.durationSeconds > 0) {
                        Text(
                            text = formatDuration(video.durationSeconds),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DownloadBadge(video.downloadState)
                }
                if (item.bodyParts.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        item.bodyParts.take(4).forEach { part ->
                            Text(
                                text = part.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (part == item.bodyParts.first()) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                        if (item.bodyParts.size > 4) {
                            Text(
                                "+${item.bodyParts.size - 4}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadBadge(state: DownloadState) {
    // Phase 13: reuse the Library's localized state labels.
    val label = when (state) {
        DownloadState.COMPLETED -> stringResource(R.string.library_state_completed)
        DownloadState.DOWNLOADING -> stringResource(R.string.library_state_downloading)
        DownloadState.FAILED -> stringResource(R.string.library_state_failed)
        DownloadState.STREAM_ONLY -> stringResource(R.string.library_state_stream_only)
        DownloadState.PENDING -> stringResource(R.string.library_state_pending)
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = when (state) {
            DownloadState.COMPLETED -> MaterialTheme.colorScheme.primary
            DownloadState.FAILED -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun StatsSection(
    state: PartDetailUiState,
    onRangeSelected: (StatsRange) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Window chips (7D/30D/90D/ALL) — same pattern as the Stats tab.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatsRange.entries.forEach { r ->
                    FilterChip(
                        selected = state.range == r,
                        onClick = { onRangeSelected(r) },
                        label = { Text(r.label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WindowStat("${state.windowMinutes}", stringResource(R.string.part_detail_min_window, state.range.label), Modifier.weight(1f))
                WindowStat("${state.windowSessions}", stringResource(R.string.part_detail_sessions_window, state.range.label), Modifier.weight(1f))
                WindowStat("${state.windowActiveDays}", stringResource(R.string.part_detail_active_days), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            TrendChart(buckets = state.buckets, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(
                    R.string.part_detail_records,
                    state.bestDayMinutes,
                    state.longestGapDays,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WindowStat(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Small Canvas bar chart of bucketed daily minutes for THIS part (pure Canvas, no chart lib). */
@Composable
private fun TrendChart(buckets: List<TrendBucket>, modifier: Modifier = Modifier) {
    val dim = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier.height(96.dp)) {
        if (buckets.isEmpty() || buckets.all { it.minutes == 0 }) {
            // Keep a visible baseline so the card doesn't collapse when empty.
            drawLine(dim, start = androidx.compose.ui.geometry.Offset(0f, size.height - 1), end = androidx.compose.ui.geometry.Offset(size.width, size.height - 1), strokeWidth = 2f)
            return@Canvas
        }
        val max = buckets.maxOf { it.minutes }.coerceAtLeast(1)
        val step = size.width / buckets.size
        val barWidth = step * 0.7f
        buckets.forEachIndexed { i, b ->
            val h = (b.minutes.toFloat() / max) * (size.height - 8f)
            drawRoundRect(
                color = if (b.containsToday) OctoBodyGreen else OctoBodyTeal,
                topLeft = androidx.compose.ui.geometry.Offset(i * step + (step - barWidth) / 2, size.height - h),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
            )
        }
    }
}

@Composable
private fun HistoryRow(row: PartHistoryRow, accent: Color) {
    val label = runCatching {
        LocalDate.parse(row.date).format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.ENGLISH))
    }.getOrElse { row.date }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
            Spacer(Modifier.width(12.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = stringResource(
                    R.string.part_detail_history_entry,
                    row.minutes,
                    row.sessions,
                    stringResource(
                        if (row.sessions == 1) {
                            R.string.part_detail_session_singular
                        } else {
                            R.string.part_detail_session_plural
                        },
                    ),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** File path when downloaded, remote i.ytimg URL as fallback (Coil caches it) — same as Library. */
private fun thumbnailModel(video: VideoEntity): Any =
    video.thumbnailPath?.let { File(it) }
        ?: "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg"

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
