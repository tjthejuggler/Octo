package com.example.octo.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.octo.R
import com.example.octo.data.local.BodyPart
import com.example.octo.data.local.Muscle
import com.example.octo.data.local.DownloadState
import com.example.octo.player.PlaybackResolution
import com.example.octo.ui.body.OctopusBodyGeometry
import com.example.octo.ui.body.OctopusBodyView
import com.example.octo.ui.components.TwoRowChips
import java.io.File

/**
 * Phase 12 — video detail ("video/{videoId}"). Phase 26 (FB26) layout rework:
 *  - the mini player shrank to a simple POSTER: still thumbnail + center play
 *    overlay; tapping it starts playback AND goes to the fullscreen player
 *    route (the full player auto-plays on entry, so one tap = play + full
 *    screen). No "from start" / "maximize" buttons on the still anymore.
 *  - the two octopus-men render in a FIXED-HEIGHT row via OctopusBodyView's
 *    fitInside letterbox mode — same size, aligned, centered in their halves,
 *    never clipped (FB25 found them mispositioned/oversized).
 *  - body-part chips take TWO rows at the bottom (shared TwoRowChips: fixed
 *    first row, scrollable second, no "+n") + the Phase 24 edit-parts pencil.
 * Session tracking / Tail stays on the full PlayerScreen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoDetailScreen(
    videoId: String,
    onNavigateBack: () -> Unit,
    onMaximize: (String) -> Unit,
    onOpenPart: (String) -> Unit,
    viewModel: VideoDetailViewModel = viewModel(
        key = "video_detail_$videoId",
        factory = remember(videoId) { VideoDetailViewModel.factory(videoId) },
    ),
) {
    val state by viewModel.uiState.collectAsState()

    // Phase 22: discard-confirmation target (pending session autoId), null = hidden.
    var showDiscardConfirm by remember { mutableStateOf<Long?>(null) }
    // Phase 24: "Edit parts" dialog visibility.
    var showEditParts by remember { mutableStateOf(false) }
    // Phase 28: Parts / Muscles toggle — shared by the tag chips AND the two
    // mini octopus-men (muscle mode renders the muscle plates on them too).
    var showMuscles by remember { mutableStateOf(false) }

    // Dual-player safety: leaving this screen (back OR Maximize→player) disposes
    // the composable and releases the mini ExoPlayer — the full player then
    // creates its own instance with exclusive audio.
    DisposableEffect(Unit) {
        onDispose { viewModel.releaseMiniPlayer() }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.video?.title ?: stringResource(R.string.player_loading),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.video_detail_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val video = state.video
        if (video == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.video_detail_unknown)) }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Phase 22: pending (unlogged) session banner ──
            state.pendingSessions.firstOrNull()?.let { pending ->
                PendingSessionBanner(
                    minutes = (pending.durationSeconds + 59) / 60, // display: ceiling minutes
                    dateLabel = pending.startedAt.toLocalDateLabel(),
                    onLogIt = { viewModel.confirmPendingSession(pending.autoId) },
                    onDiscard = {
                        showDiscardConfirm = pending.autoId
                    },
                )
            }

            // ── Meta row: channel · duration · download state ──
            MetaRow(state)

            // ── FB26 poster: still + center play overlay. One tap = play AND
            // fullscreen (the full player route auto-plays on entry); the mini
            // ExoPlayer is no longer instantiated here at all.
            PosterCard(
                state = state,
                onPlay = {
                    viewModel.releaseMiniPlayer()
                    onMaximize(videoId)
                },
                onRetryDownload = viewModel::retryDownload,
            )

            // ── Mini octopus-men: preview of the dashboard tier colors; the
            // TagsCard Parts/Muscles toggle also swaps these to muscle plates ──
            MiniBodyMapCard(
                state = state,
                showMuscles = showMuscles,
                onOpenPart = onOpenPart,
            )

            // ── Tappable body-part chips + provenance + edit affordance ──
            // Shown whenever analysis finished — an EMPTY part set is a valid
            // DONE result (e.g. a meditation video) and must still be editable.
            if (state.isAnalysisDone) {
                TagsCard(
                    state = state,
                    showMuscles = showMuscles,
                    onToggle = { showMuscles = it },
                    onOpenPart = onOpenPart,
                    onEditParts = { showEditParts = true },
                )
            }
        }
    }

    if (showEditParts) {
        EditPartsDialog(
            current = state.bodyParts.toSet(),
            onSave = { selected ->
                viewModel.saveManualParts(selected)
                showEditParts = false
            },
            onDismiss = { showEditParts = false },
        )
    }

    showDiscardConfirm?.let { rowId ->
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = null },
            title = { Text(stringResource(R.string.body_discard_confirm_title)) },
            text = { Text(stringResource(R.string.body_discard_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.discardPendingSession(rowId)
                    showDiscardConfirm = null
                }) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = null }) {
                    Text(stringResource(R.string.player_cancel))
                }
            },
        )
    }
}

/** Phase 22: persistent banner for a played-but-unlogged session of this video. */
@Composable
private fun PendingSessionBanner(
    minutes: Int,
    dateLabel: String,
    onLogIt: () -> Unit,
    onDiscard: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.video_detail_pending_banner, minutes),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (dateLabel.isNotBlank()) {
                    Text(
                        text = dateLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = onLogIt) { Text(stringResource(R.string.action_log_it)) }
            TextButton(onClick = onDiscard) { Text(stringResource(R.string.action_discard)) }
        }
    }
}

private fun Long.toLocalDateLabel(): String = try {
    java.time.Instant.ofEpochMilli(this)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .let { if (it == java.time.LocalDate.now()) "" else it.toString() }
} catch (_: Exception) {
    ""
}

@Composable
private fun MetaRow(state: VideoDetailUiState) {
    val video = state.video ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (video.channel.isNotBlank()) {
            Text(
                text = video.channel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (video.durationSeconds > 0) {
            Text(
                text = formatDuration(video.durationSeconds),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DownloadStateChip(video.downloadState)
        if (state.isAnalyzing) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.video_detail_analyzing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DownloadStateChip(state: DownloadState) {
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
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun PosterCard(
    state: VideoDetailUiState,
    onPlay: () -> Unit,
    onRetryDownload: () -> Unit,
) {
    val video = state.video
    Card(modifier = Modifier.fillMaxWidth()) {
        if (state.resolution is PlaybackResolution.Ready && video != null) {
            // FB26 poster: local still (thumbnailPath) with fallback to the YT
            // thumb, a single center play affordance. Tapping plays AND goes
            // fullscreen (no separate from-start/maximize buttons).
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clickable(onClick = onPlay),
            ) {
                AsyncImage(
                    model = video.thumbnailPath?.let { File(it) }
                        ?: "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg",
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.video_detail_play),
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(64.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .padding(10.dp),
                )
            }
        } else {
            // MissingFiles / not yet resolved: friendly error + retry when FAILED.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("🐢", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.video_detail_missing_files),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (video?.downloadState == DownloadState.FAILED) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onRetryDownload) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.video_detail_retry_download))
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniBodyMapCard(
    state: VideoDetailUiState,
    showMuscles: Boolean,
    onOpenPart: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            // FB26: front AND back figures in a FIXED-HEIGHT row drawn via the
            // fitInside letterbox mode — min(w,h) scale, centered in each half,
            // identical size, never clipped (FB25 pixel-scan showed the old
            // aspect-ratio cells overflowing the card bottom and reading
            // different sizes). 180.dp keeps the whole screen no-scroll.
            // Phase 28: previewMode colors — covered parts render tier(count+1),
            // everything else idle red; muscle mode composites the plates.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OctopusBodyView(
                    todayCounts = state.todayCounts,
                    onRegionTap = { part -> onOpenPart(part.id) },
                    previewMode = true,
                    previewTargets = state.videoTargets,
                    previewMuscles = state.videoMuscles,
                    side = OctopusBodyGeometry.Side.FRONT,
                    fitInside = true,
                    muscleMode = showMuscles,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
                OctopusBodyView(
                    todayCounts = state.todayCounts,
                    onRegionTap = { part -> onOpenPart(part.id) },
                    previewMode = true,
                    previewTargets = state.videoTargets,
                    previewMuscles = state.videoMuscles,
                    side = OctopusBodyGeometry.Side.BACK,
                    fitInside = true,
                    muscleMode = showMuscles,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        }
    }
}


/** Simple label from a muscle id-ish string ("pectoralis_major" -> "Pectoralis major"). */
private fun muscleLabel(muscle: Muscle): String = muscle.displayName

/**
 * Tags card with a Parts / Muscles segmented toggle. Parts = the canonical
 * 16 categories (tappable, editable via pencil); Muscles = the vision-layer
 * detail, grouped implicitly by chip order. The toggle only appears when the
 * video actually HAS muscle detail.
 */
@Composable
private fun TagsCard(
    state: VideoDetailUiState,
    showMuscles: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenPart: (String) -> Unit,
    onEditParts: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // ── Parts / Muscles toggle (only when muscle detail exists) ──
        if (state.muscles.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !showMuscles,
                        onClick = { onToggle(false) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) {
                        Text(
                            stringResource(R.string.video_detail_toggle_parts),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    SegmentedButton(
                        selected = showMuscles,
                        onClick = { onToggle(true) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) {
                        Text(
                            stringResource(R.string.video_detail_toggle_muscles),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }

        if (showMuscles && state.muscles.isNotEmpty()) {
            // ── Exact muscles (vision analysis) — wrapped chips, not tappable ──
            TwoRowChips(
                items = state.muscles,
                modifier = Modifier.fillMaxWidth(),
                itemSpacing = 6.dp,
                rowSpacing = 6.dp,
            ) { muscle ->
                AssistChip(
                    onClick = { onOpenPart(muscle.parent.id) },
                    label = { Text(muscleLabel(muscle), style = MaterialTheme.typography.labelSmall) },
                )
            }
        } else {
            // ── Canonical 16-part categories (tappable, editable) ──
            TwoRowChips(
                items = state.bodyParts,
                modifier = Modifier.fillMaxWidth(),
                itemSpacing = 6.dp,
                rowSpacing = 6.dp,
            ) { part ->
                AssistChip(
                    onClick = { onOpenPart(part.id) },
                    label = { Text(part.displayName, style = MaterialTheme.typography.labelSmall) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onEditParts, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.video_detail_edit_parts),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Phase 24: full 16-part checkbox dialog. Save replaces the link set + stamps
 * MANUAL provenance (VideoDao.applyManualParts). Confirmed sessions keep their
 * creditedBodyPartsJson snapshots — the note says so explicitly.
 */
@Composable
private fun EditPartsDialog(
    current: Set<BodyPart>,
    onSave: (Set<BodyPart>) -> Unit,
    onDismiss: () -> Unit,
) {
    val selected = remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_parts_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.edit_parts_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 380.dp),
                ) {
                    BodyPart.entries.forEach { part ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected.value = if (part in selected.value) {
                                        selected.value - part
                                    } else {
                                        selected.value + part
                                    }
                                }
                                .padding(vertical = 2.dp),
                        ) {
                            Checkbox(
                                checked = part in selected.value,
                                onCheckedChange = {
                                    selected.value = if (it) selected.value + part else selected.value - part
                                },
                            )
                            Text(text = part.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.edit_parts_manual_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.credited_snapshot_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected.value) }) {
                Text(stringResource(R.string.edit_parts_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.edit_parts_cancel)) }
        },
    )
}

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
