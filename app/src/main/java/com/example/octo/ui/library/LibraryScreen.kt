package com.example.octo.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.octo.R
import com.example.octo.data.local.BodyPart
import com.example.octo.data.local.DownloadState
import com.example.octo.ui.components.GlassCard
import com.example.octo.ui.components.GlassTone
import com.example.octo.ui.components.OctoHeader
import com.example.octo.ui.components.TwoRowChips
import java.io.File

/**
 * Library screen — Phase 21 (feedback round 4), card behavior reworked Phase 26 (FB26):
 *  - always-visible compact search field (title/channel, case-insensitive contains)
 *  - horizontal body-part filter chip row (multi-select, OR within parts; AND with text)
 *  - compact video cards (~half the previous vertical footprint: leading 96dp thumbnail,
 *    1–2 line title, single meta row, part tags on two rows)
 *  - FB26: clicking ANYWHERE on a card opens the VIDEO DETAIL screen — the explicit
 *    play button is gone. The overflow menu keeps Analyze/Delete (+ their confirms);
 *    FAILED cards additionally show a small retry icon next to the menu.
 * State survives navigation because query + part selection live in [LibraryViewModel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenVideo: (String) -> Unit = {},
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val items by viewModel.items.collectAsState()
    val filtered by viewModel.filteredItems.collectAsState()
    val query by viewModel.query.collectAsState()
    val selectedParts by viewModel.parts.collectAsState()
    val sort by viewModel.sort.collectAsState()
    val sortDesc by viewModel.sortDesc.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LibraryViewModel.VideoUiItem?>(null) }
    var addError by remember { mutableStateOf<Boolean>(false) }
    // Phase 24: Analyze pressed on a DONE+MANUAL row → confirm before replacing edits.
    var analyzeConfirmTarget by remember { mutableStateOf<LibraryViewModel.VideoUiItem?>(null) }
    val filtersActive = query.isNotBlank() || selectedParts.isNotEmpty()

    // contentWindowInsets=0: the root app scaffold already applies the system-bar
    // insets — a second scaffold here would double-pad and push the header down.
    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true; addError = false }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.library_add_url))
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // 12dp horizontal to match the Body dashboard's content grid; the
                // shared OctoHeader then starts at the exact same x as Body's.
                .padding(horizontal = 12.dp),
        ) {
            // Shared mascot header — identical to Body/Stats.
            OctoHeader(
                title = stringResource(R.string.library_title),
                subtitle = stringResource(R.string.library_subtitle),
            )
            // --- Compressed header: search (+ parts-filter popup) + (sort | part) chips ---
            if (items.isNotEmpty()) {
                var partsFilterOpen by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        Row {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setQuery("") }) {
                                    Icon(
                                        Icons.Filled.Clear,
                                        contentDescription = stringResource(R.string.library_filter_clear),
                                    )
                                }
                            }
                            // Body-part filter popup anchor — badge dot when active.
                            IconButton(onClick = { partsFilterOpen = true }) {
                                Icon(
                                    Icons.Filled.FilterList,
                                    contentDescription = stringResource(R.string.library_filter_parts),
                                    tint = if (selectedParts.isEmpty()) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                )
                            }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.library_search_hint)) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                )

                PartsFilterPopup(
                    expanded = partsFilterOpen,
                    selected = selectedParts,
                    onToggle = viewModel::togglePart,
                    onClear = viewModel::clearFilters,
                    onDismiss = { partsFilterOpen = false },
                )

                // ONE compact chip row: sorts first, then a divider chip, then parts.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                ) {
                    LibrarySort.entries.forEach { mode ->
                        FilterChip(
                            selected = mode == sort,
                            onClick = { viewModel.setSort(mode) },
                            label = {
                                Text(mode.label, style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                    FilterChip(
                        selected = false,
                        onClick = viewModel::toggleSortDirection,
                        label = { Text(if (sortDesc) "↓" else "↑") },
                    )

                    // Visual separator between sorts and part filters.
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(24.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )

                    if (selectedParts.isNotEmpty()) {
                        FilterChip(
                            selected = true,
                            onClick = viewModel::clearFilters,
                            label = { Text(stringResource(R.string.library_filter_clear)) },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Clear,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                            },
                        )
                    }
                    BodyPart.entries.forEach { part ->
                        FilterChip(
                            selected = part in selectedParts,
                            onClick = { viewModel.togglePart(part) },
                            label = {
                                Text(part.displayName, style = MaterialTheme.typography.labelSmall)
                            },
                        )
                    }
                }
            }

            when {
                items.isEmpty() -> LibraryEmptyState()
                filtered.isEmpty() -> NoMatchState(
                    onClear = viewModel::clearFilters,
                    modifier = Modifier.weight(1f),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.video.id }) { item ->
                        VideoCard(
                            item = item,
                            onOpen = { onOpenVideo(item.video.id) },
                            onRetry = { viewModel.retry(item.video.id) },
                            onAnalyze = {
                                if (item.needsManualConfirmBeforeAnalyze) {
                                    analyzeConfirmTarget = item
                                } else {
                                    viewModel.analyze(item.video.id)
                                }
                            },
                            onDelete = { deleteTarget = item },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddUrlDialog(
            onDismiss = { showAddDialog = false; addError = false },
            onAdd = { url ->
                viewModel.addUrl(url) { ok ->
                    if (ok) {
                        showAddDialog = false
                        addError = false
                    } else {
                        addError = true
                    }
                }
            },
            showError = addError,
        )
    }

    analyzeConfirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { analyzeConfirmTarget = null },
            title = { Text(stringResource(R.string.analyze_manual_confirm_title)) },
            text = { Text(stringResource(R.string.analyze_manual_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.analyzeForced(target.video.id)
                    analyzeConfirmTarget = null
                }) { Text(stringResource(R.string.analyze_manual_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { analyzeConfirmTarget = null }) {
                    Text(stringResource(R.string.library_cancel))
                }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.library_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.library_delete_body) +
                        "\n\n" + target.video.title,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(target.video.id)
                        deleteTarget = null
                    },
                ) { Text(stringResource(R.string.library_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.library_cancel))
                }
            },
        )
    }
}

/**
 * Body-part filter dialog, centered on screen in a glass pane matching the
 * app's sea-glass language: scrollable check list of all 16 parts (multi-
 * select, OR semantics) + Clear action. Drives the same
 * [LibraryViewModel.togglePart] state as the chip row.
 */
@Composable
private fun PartsFilterPopup(
    expanded: Boolean,
    selected: Set<BodyPart>,
    onToggle: (BodyPart) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (!expanded) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.Transparent,
        title = { Text(stringResource(R.string.library_filter_parts_title)) },
        text = {
            GlassCard(tone = GlassTone.Deep) {
                Column(
                    Modifier
                        .heightIn(max = 430.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (selected.isNotEmpty()) {
                        TextButton(onClick = onClear) {
                            Icon(Icons.Filled.Clear, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.library_filter_clear))
                        }
                    }
                    BodyPart.entries.forEach { part ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggle(part) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                part.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f),
                            )
                            if (part in selected) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

/**
 * Phase 21 compact card; FB26 rework: the whole card is a single click target
 * that opens the video-detail screen (no explicit play button). Part tags use
 * the shared two-row layout (row 1 fixed, row 2 scrollable, no "+n").
 */
@Composable
private fun VideoCard(
    item: LibraryViewModel.VideoUiItem,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onAnalyze: () -> Unit,
    onDelete: () -> Unit,
) {
    val video = item.video
    var menuOpen by remember { mutableStateOf(false) }
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(96.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                AsyncImage(
                    model = thumbnailModel(video),
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (video.durationSeconds > 0) {
                    Text(
                        text = formatDuration(video.durationSeconds),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(2.dp)
                            .background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                RoundedCornerShape(4.dp),
                            )
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Meta row: channel · duration · state. A completed download is the
                // EXPECTED state (everything in the library is downloaded), so it
                // gets a tiny check icon instead of a text chip — the text label
                // only appears for noteworthy states (downloading/failed/queued).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val meta = listOfNotNull(
                        video.channel.takeIf { it.isNotBlank() },
                        formatDuration(video.durationSeconds).takeIf { video.durationSeconds > 0 },
                    ).joinToString(" · ")
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    if (item.video.downloadState == DownloadState.COMPLETED && !item.isDownloading) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.library_state_completed),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                    } else {
                        StateLabel(item)
                    }
                }

                // Part tags across TWO rows (shared FB26 layout): row 1 fixed,
                // row 2 scrolls — all parts reachable, no "+n" (FB25/26 feedback).
                if (item.isAnalyzing || item.bodyParts.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (item.isAnalyzing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                        TwoRowChips(items = item.bodyParts) { part ->
                            PartTag(part.displayName)
                        }
                    }
                }

                if (item.isDownloading) {
                    LinearProgressIndicator(
                        progress = { item.progress?.fraction ?: 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    )
                }
            }

            // Compact icon actions: play (or retry when failed) + overflow menu.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // FB26: no play button — the card itself opens video detail.
                // A small retry icon stays only on FAILED cards (explicitly allowed).
                if (item.isFailed) {
                    IconButton(onClick = onRetry, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.library_retry),
                            tint = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.library_menu),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (item.canAnalyze) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.library_analyze)) },
                                onClick = {
                                    menuOpen = false
                                    onAnalyze()
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.library_delete)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Delete, contentDescription = null)
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Tiny body-part tag used on the card's (up to two) tag rows. */
@Composable
private fun PartTag(text: String) {
    Text(
        text = text,
        maxLines = 1,
        softWrap = false,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** Phase 21: state as a tiny colored text label instead of a full AssistChip. */
@Composable
private fun StateLabel(item: LibraryViewModel.VideoUiItem) {
    val (label, color) = when {
        item.isDownloading -> stringResource(R.string.library_state_downloading) to
            MaterialTheme.colorScheme.tertiary

        item.video.downloadState == DownloadState.COMPLETED -> stringResource(R.string.library_state_completed) to
            MaterialTheme.colorScheme.primary

        item.isFailed -> stringResource(R.string.library_state_failed) to
            MaterialTheme.colorScheme.error

        item.video.downloadState == DownloadState.STREAM_ONLY -> stringResource(R.string.library_state_stream_only) to
            MaterialTheme.colorScheme.secondary

        else -> stringResource(R.string.library_state_pending) to
            MaterialTheme.colorScheme.secondary
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** File path when downloaded, remote i.ytimg URL as fallback (Coil caches it). */
private fun thumbnailModel(video: com.example.octo.data.local.VideoEntity): Any =
    video.thumbnailPath?.let { File(it) }
        ?: "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg"

private fun formatDuration(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) {
        "%d:%02d:%02d".format(h, m, s)
    } else {
        "%d:%02d".format(m, s)
    }
}

@Composable
private fun AddUrlDialog(
    showError: Boolean,
    onDismiss: () -> Unit,
    onAdd: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_add_url)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.library_url_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showError) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.library_add_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onAdd(text.trim()) },
                enabled = text.isNotBlank(),
            ) { Text(stringResource(R.string.library_add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel)) }
        },
    )
}

@Composable
private fun LibraryEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🐙", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Phase 21: filters active but nothing matched. */
@Composable
private fun NoMatchState(onClear: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🦑", style = MaterialTheme.typography.displayLarge)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.library_no_match),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onClear) {
            Text(stringResource(R.string.library_filter_clear))
        }
    }
}
