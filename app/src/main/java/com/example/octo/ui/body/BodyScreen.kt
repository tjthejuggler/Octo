package com.example.octo.ui.body

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.octo.R
import com.example.octo.data.local.BodyPart
import com.example.octo.data.local.SessionWithTitle
import androidx.compose.ui.res.stringResource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Body dashboard (Phase 14 cleanup): fits ONE screen with NO scrolling on
 * typical phones. Compact title row (head-only mascot + "Octo" + inline date
 * navigation + a discreet "parts" tag button), the octopus-man figure as the
 * weight()-driven centerpiece with the progress ring and % badge, and two tiny
 * unboxed corner stat glyphs (minutes / streak) over the figure area. The 16
 * body parts live behind the tag button in a light ModalBottomSheet (tap a
 * part → "part/{bodyPartId}"); no inline legend anymore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyScreen(
    onOpenPart: (BodyPart) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BodyViewModel = viewModel(factory = BodyViewModel.Factory),
) {
    val dashboard by viewModel.dashboard.collectAsState()
    val pendingSessions by viewModel.pendingSessions.collectAsState()
    val confirmedToday by viewModel.confirmedForSelectedDay.collectAsState()
    var showPartsSheet by remember { mutableStateOf(false) }
    var showReviewSheet by remember { mutableStateOf(false) }
    var showUndoSheet by remember { mutableStateOf(false) }
    var discardTarget by remember { mutableStateOf<SessionWithTitle?>(null) }
    var undoTarget by remember { mutableStateOf<SessionWithTitle?>(null) }

    // ── Phase 20/24 flip state (FB5 rework v3) ──
    // The whole angle lives in ONE plain State written synchronously by the drag
    // (finger-tracking) and by a single settle coroutine that animates a plain
    // var in a frame loop — no Animatable mutators from gesture context, and the
    // settle coroutine owns the angle exclusively until the next drag cancels it.
    var angleState by remember { mutableFloatStateOf(0f) }
    var settleJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val scope = rememberCoroutineScope()

    val normalized = ((angleState % 360f) + 360f) % 360f
    val currentSide = if (normalized < 90f || normalized > 270f) {
        OctopusBodyGeometry.Side.FRONT
    } else {
        OctopusBodyGeometry.Side.BACK
    }

    /**
     * Animate [angleState] to [targetAbs] with a critically-damped exponential
     * approach driven by the composition frame clock. Only [settleJob] writes
     * the angle while it is alive; a new drag cancels it first.
     */
    fun animateAngleTo(targetAbs: Float) {
        settleJob?.cancel()
        settleJob = scope.launch {
            var current = angleState
            var last = androidx.compose.runtime.withFrameNanos { it }
            while (kotlin.math.abs(targetAbs - current) > 0.05f) {
                androidx.compose.runtime.withFrameNanos { now ->
                    val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                    last = now
                    // Exponential approach: ~63% of the gap per 90ms.
                    current += (targetAbs - current) * (1f - kotlin.math.exp(-dt / 0.09f))
                    angleState = current
                }
            }
            angleState = targetAbs
        }
    }

    /** Drag release: nearest rest (…0/±180…) from the LIVE angle + flick bias. */
    val settleToNearest: (Float) -> Unit = { flickBiasDegPerSec ->
        val projected = angleState + flickBiasDegPerSec * FLICK_BIAS_SECONDS
        animateAngleTo(kotlin.math.round(projected / 180f) * 180f)
    }

    /** Pill tap-toggle: one more half-turn in [direction]. */
    val flipTo: (Int) -> Unit = { direction ->
        animateAngleTo((kotlin.math.round(angleState / 180f) + direction) * 180f)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TitleBar(
            date = dashboard.selectedDate,
            isToday = dashboard.isToday,
            showUndo = confirmedToday.isNotEmpty(),
            onShowUndo = { showUndoSheet = true },
            onPrevious = viewModel::selectPreviousDay,
            onNext = viewModel::selectNextDay,
            onToday = viewModel::backToToday,
            onShowParts = { showPartsSheet = true },
        )

        // Phase 22: subtle banner whenever any played-but-unlogged session exists.
        if (pendingSessions.isNotEmpty()) {
            PendingReviewBanner(
                count = pendingSessions.size,
                onReview = { showReviewSheet = true },
            )
        }

        if (dashboard.isToday && dashboard.totalMinutes == 0) {
            EmptyHint()
        }

        // Figure centerpiece: everything else is fixed-height chrome, so the
        // body map flexes to exactly the remaining space — never scrolls.
        // Phase 16 polish: the figure box is capped at 92% of the flex height
        // with 12dp side insets so the octopus-man floats centered with air
        // above/below and clear of the side edges, instead of hugging the
        // title row, screen edges, and the bottom nav (the ring then hugs the
        // smaller figure, and the corner glyphs stay outside both).
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.92f)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Animated sweep ring hugging the figure (matchParentSize inside
                // the figure-sized inner box).
                PercentRing(
                    percent = dashboard.percent,
                    modifier = Modifier.matchParentSize(),
                )
                // No fillMaxSize here: OctopusBodyView sizes itself via
                // aspectRatio, so it fits (and centers in) whatever height the
                // weight(1f) box provides — the ring then hugs the figure.
                // Phase 20/24: drag the man — he turns with the finger (content
                // swaps at ±90°), settles to the nearest side on release; the
                // pill below stays as a tap-to-toggle.
                val degPerPx = degreesPerPx()
                FlippableBodyView(
                    stretchedMinutes = dashboard.minutesByPart,
                    onRegionTap = onOpenPart,
                    flipAngle = angleState,
                    side = currentSide,
                    onDragDelta = { deltaPx ->
                        // Synchronous finger-tracking: a live settle coroutine is
                        // cancelled first (its last written value stays in
                        // [angleState]); then the delta applies 1:1.
                        settleJob?.cancel()
                        settleJob = null
                        angleState += deltaPx * degPerPx
                    },
                    onSettle = { flickBiasDeg -> settleToNearest(flickBiasDeg) },
                )
                PercentBadge(
                    percent = dashboard.percent,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 2.dp),
                )
            }

            // Phase 14: the only remaining stats — minutes + streak — as tiny
            // unboxed icon glyphs in the figure-area corner. Hidden at 0 so the
            // empty state stays airy.
            CornerStats(
                totalMinutes = dashboard.totalMinutes,
                streakDays = dashboard.streakDays,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 12.dp, end = 16.dp),
            )

            // Phase 20: side indicator — doubles as a tap-to-flip toggle so
            // the turn is discoverable even without knowing the swipe.
            // FB5: toggling animates through the same settle path.
            SideIndicator(
                side = currentSide,
                onToggle = { flipTo(if (currentSide == OctopusBodyGeometry.Side.FRONT) 1 else -1) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 2.dp),
            )
        }

        Spacer(Modifier.height(8.dp))
    }

    if (showPartsSheet) {
        PartsSheet(
            minutesByPart = dashboard.minutesByPart,
            onPartTap = { part ->
                showPartsSheet = false
                onOpenPart(part)
            },
            onDismiss = { showPartsSheet = false },
        )
    }

    if (showReviewSheet) {
        SessionReviewSheet(
            title = stringResource(R.string.body_review_title),
            hint = stringResource(R.string.body_review_hint),
            sessions = pendingSessions,
            showLogAll = true,
            onLog = viewModel::confirmPending,
            onLogAll = viewModel::confirmAllPending,
            onDiscardOrUndo = { discardTarget = it },
            onDismiss = { showReviewSheet = false },
        )
    }

    if (showUndoSheet) {
        SessionReviewSheet(
            title = stringResource(R.string.body_undo_title),
            hint = stringResource(R.string.body_undo_hint),
            sessions = confirmedToday,
            showLogAll = false,
            onLog = {},
            onLogAll = {},
            onDiscardOrUndo = { undoTarget = it },
            onDismiss = { showUndoSheet = false },
        )
    }

    discardTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { discardTarget = null },
            title = { Text(stringResource(R.string.body_discard_confirm_title)) },
            text = { Text(stringResource(R.string.body_discard_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.discardPending(row.autoId)
                    discardTarget = null
                }) { Text(stringResource(R.string.action_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { discardTarget = null }) { Text("Cancel") }
            },
        )
    }

    undoTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { undoTarget = null },
            title = { Text(stringResource(R.string.body_undo_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.body_undo_confirm_body,
                        row.title ?: "Session",
                        (row.durationSeconds + 59) / 60,
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.undoConfirmed(row.autoId)
                    undoTarget = null
                }) { Text(stringResource(R.string.action_undo)) }
            },
            dismissButton = {
                TextButton(onClick = { undoTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/** Phase 22: subtle banner under the title row when unlogged sessions exist. */
@Composable
private fun PendingReviewBanner(count: Int, onReview: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onReview)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = stringResource(R.string.body_pending_banner, count, if (count == 1) "" else "s"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Review",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = OctoBodyTeal,
            )
        }
    }
}

/**
 * Phase 22 shared sheet: pending sessions ([showLogAll] = true → Log it / Discard)
 * or confirmed sessions of a day (false → Undo). Rows show title, minutes, date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionReviewSheet(
    title: String,
    hint: String,
    sessions: List<SessionWithTitle>,
    showLogAll: Boolean,
    onLog: (Long) -> Unit,
    onLogAll: () -> Unit,
    onDiscardOrUndo: (SessionWithTitle) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            if (sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.body_undo_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            sessions.forEach { row ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.title ?: "(deleted video)",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${(row.durationSeconds + 59) / 60} min · " +
                                row.startedAt.toLocalDateLabel(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (showLogAll) {
                        TextButton(onClick = { onLog(row.autoId) }) {
                            Text(stringResource(R.string.action_log_it))
                        }
                    }
                    TextButton(onClick = { onDiscardOrUndo(row) }) {
                        Text(
                            stringResource(
                                if (showLogAll) R.string.action_discard else R.string.action_undo
                            )
                        )
                    }
                }
            }
            if (showLogAll && sessions.size > 1) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = onLogAll, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_log_all))
                }
            }
        }
    }
}

private fun Long.toLocalDateLabel(): String = try {
    java.time.Instant.ofEpochMilli(this)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()
} catch (_: Exception) {
    ""
}

/** One compact row: mascot, title, inline date navigation, and the parts tag button. */
@Composable
private fun TitleBar(
    date: LocalDate,
    isToday: Boolean,
    showUndo: Boolean,
    onShowUndo: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onShowParts: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.drawable.octo_mascot),
            contentDescription = null, // decorative
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text("Octo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        if (!isToday) {
            TextButton(
                onClick = onToday,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Text("Today", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.weight(1f))
        // Phase 14: discreet tag button replacing the always-on legend grid —
        // opens the parts bottom sheet.
        // Phase 22: undo affordance — shown only when the viewed day has logged sessions.
        if (showUndo) {
            IconButton(
                onClick = onShowUndo,
                modifier = Modifier
                    .size(32.dp)
                    .alpha(0.75f)
                    .semantics { contentDescription = "Undo logged sessions for this day" },
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        IconButton(
            onClick = onShowParts,
            modifier = Modifier
                .size(32.dp)
                .alpha(0.75f)
                .semantics { contentDescription = "Show body parts legend" },
        ) {
            Icon(
                imageVector = Icons.Outlined.Sell,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous day")
        }
        Text(
            text = dateLabel(date),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(128.dp),
        )
        IconButton(onClick = onNext, enabled = !isToday, modifier = Modifier.size(36.dp)) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next day")
        }
    }
}

private fun dateLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> date.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
            .replaceFirstChar { it.uppercase(Locale.getDefault()) }
    } + " · " + date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
}

/** Circular progress ring showing % of the 16 regions stretched on the selected day. */
@Composable
private fun PercentRing(percent: Int, modifier: Modifier = Modifier) {
    val sweep by animateFloatAsState(
        targetValue = percent / 100f,
        animationSpec = tween(durationMillis = 900),
        label = "ringSweep",
    )
    val ringColor = if (percent > 0) OctoBodyTeal else MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier.semantics {
        contentDescription = "$percent percent of body stretched"
    }) {
        val stroke = 8.dp.toPx()
        val inset = stroke / 2 + 2.dp.toPx()
        drawArc(
            color = ringColor.copy(alpha = 0.25f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
        )
        drawArc(
            brush = Brush.sweepGradient(listOf(OctoBodyTeal, OctoBodyGreen)),
            startAngle = -90f,
            sweepAngle = 360f * sweep,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            topLeft = Offset(inset, inset),
            size = Size(size.width - inset * 2, size.height - inset * 2),
        )
    }
}

/** The single % presentation on the dashboard — small pill riding the ring top. */
@Composable
private fun PercentBadge(percent: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = if (percent > 0) OctoBodyTeal else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

/**
 * Phase 14: minutes + streak as label-less corner glyphs — a clock icon and a
 * flame icon with small accent numbers, no card/box behind them. Each glyph is
 * ~28dp tall so it whispers rather than competes with the figure. Hidden
 * entirely when both values are 0 (fresh day / empty state).
 */
@Composable
private fun CornerStats(
    totalMinutes: Int,
    streakDays: Int,
    modifier: Modifier = Modifier,
) {
    if (totalMinutes == 0 && streakDays == 0) return
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.End,
    ) {
        if (totalMinutes > 0) {
            CornerStat(
                icon = Icons.Outlined.Schedule,
                value = "$totalMinutes",
                suffix = "m",
                contentDescription = "$totalMinutes minutes today",
            )
        }
        if (streakDays > 0) {
            CornerStat(
                icon = Icons.Outlined.LocalFireDepartment,
                value = "$streakDays",
                contentDescription = "$streakDays day streak",
            )
        }
    }
}

@Composable
private fun CornerStat(
    icon: ImageVector,
    value: String,
    suffix: String = "",
    contentDescription: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null, // value carries the semantics below
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = value + suffix,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = OctoBodyTeal,
            modifier = Modifier.semantics { this.contentDescription = contentDescription },
        )
    }
}

/**
 * Phase 14: the 16 body parts in a light Material3 bottom sheet, each with its
 * current state color dot (same [octoRegionColor] mapping as the figure).
 * Tap → dismiss + navigate to "part/{bodyPartId}"; backdrop tap dismisses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartsSheet(
    minutesByPart: Map<BodyPart, Int>,
    onPartTap: (BodyPart) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text(
                text = "Body parts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            BodyPart.entries.chunked(2).forEach { rowParts ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (part in rowParts) {
                        PartRow(
                            part = part,
                            minutes = minutesByPart[part] ?: 0,
                            onTap = { onPartTap(part) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Odd row count padding (16 is even, but stay robust).
                    if (rowParts.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun PartRow(
    part: BodyPart,
    minutes: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stretched = minutes > 0
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(
                    if (stretched) {
                        octoRegionColor(minutes)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = part.displayName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (stretched) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (stretched) {
            Text(
                text = "${minutes}m",
                style = MaterialTheme.typography.labelMedium,
                color = OctoBodyTeal,
            )
        }
    }
}

@Composable
private fun EmptyHint() {
    Text(
        text = "Your octopus is feeling stiff — stretch something! 🐙",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

// ── Phase 14 cleanup: inline legend chips + the 4-square stat row removed.
// Parts → PartsSheet (behind the tag button); % → ring badge only;
// minutes/streak → CornerStats; sessions counter dropped entirely. ──

// ── Phase 20: front/back flip (Phase 24: drag-tracking) ──

/**
 * Drag→angle gain: horizontal pixels (in dp) to rotation degrees. Tuned so a
 * ~half-screen-width drag ≈ 180° — on a 411dp-wide phone, ~205dp of drag makes
 * a full half-turn, i.e. 0.88°/dp ≈ 0.29°/px.
 */
private const val DEGREES_PER_DP = 0.9f

/** px→dp factor captured once per composition (density-stable in practice). */
@Composable
private fun degreesPerPx(): Float {
    val density = LocalDensity.current
    return remember(density) { DEGREES_PER_DP / density.density }
}

/**
 * Flick bias window: release velocity (deg/s) × this = degrees of projected
 * travel for choosing the nearest rest side. 0.12 s → a 1500 deg/s flick
 * projects +180°, completing the half-turn from a short fast swipe.
 */
private const val FLICK_BIAS_SECONDS = 0.12f

/**
 * Phase 20, REWORKED in Phase 24 (FB5): the figure turns WITH the finger.
 *
 * A single absolute rotation angle drives graphicsLayer rotationY (modest camera
 * distance so the squash reads as a turn, not a card flip). During the drag the
 * angle follows the pointer 1:1 ([DEGREES_PER_DP] per pixel); the CONTENT swaps
 * live at every ±90° crossing (side derived from the live angle) so the octopus
 * never appears mirrored. On release (or cancel) the angle springs to the
 * nearest resting side (…0/±180…) with the release velocity folded in via
 * [FLICK_BIAS_SECONDS] — a quick flick completes the half-turn.
 *
 * Taps still reach the body regions: detectHorizontalDragGestures has built-in
 * touch slop, so a stationary touch is never consumed as a drag and falls
 * through to [OctopusBodyView]'s own tap detector.
 */
@Composable
private fun FlippableBodyView(
    stretchedMinutes: Map<BodyPart, Int>,
    onRegionTap: (BodyPart) -> Unit,
    flipAngle: Float,
    side: OctopusBodyGeometry.Side,
    onDragDelta: (Float) -> Unit,
    onSettle: (flickBiasDegPerSec: Float) -> Unit,
) {
    val density = LocalDensity.current
    // Captured once (composable context) — the gesture callbacks are non-composable.
    val degPerPx = degreesPerPx()
    // Rolling velocity estimate: last few drag deltas → deg/s for the settle bias.
    val recentDeltas = remember { androidx.compose.runtime.mutableStateListOf<Float>() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                rotationY = flipAngle
                cameraDistance = 14f * density.density * 160f
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { recentDeltas.clear() },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        recentDeltas.add(dragAmount)
                        if (recentDeltas.size > 5) recentDeltas.removeAt(0)
                        onDragDelta(dragAmount)
                    },
                    onDragEnd = {
                        // Estimate release velocity: avg px/event over the last
                        // ~5 events, assuming ~60 Hz pointer sampling.
                        val avgPx = if (recentDeltas.isEmpty()) 0f
                        else recentDeltas.average().toFloat()
                        onSettle(avgPx * 60f * degPerPx)
                        recentDeltas.clear()
                    },
                    onDragCancel = { onSettle(0f) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        OctopusBodyView(
            stretchedMinutes = stretchedMinutes,
            onRegionTap = onRegionTap,
            side = side,
        )
    }
}

/**
 * Tiny "Front ● / Back ●" pill under the figure: the active side is accented;
 * tapping it toggles (discoverability fallback for the swipe gesture).
 */
@Composable
private fun SideIndicator(
    side: OctopusBodyGeometry.Side,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(16.dp)),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IndicatorDot(
                active = side == OctopusBodyGeometry.Side.FRONT,
                label = "Front",
            )
            IndicatorDot(
                active = side == OctopusBodyGeometry.Side.BACK,
                label = "Back",
            )
        }
    }
}

@Composable
private fun IndicatorDot(active: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) OctoBodyTeal else MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) OctoBodyTeal else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
