package com.example.octo.ui.player

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.octo.R
import com.example.octo.ipc.TailIntegrationClient
import com.example.octo.player.PlaybackResolution

/**
 * Phase 4 player (task spec §4): Media3 [PlayerView] + big play-time-only timer +
 * Finish Session + Tail feedback + confirm-on-back. The player instance survives
 * rotation because it lives in [PlayerViewModel] (application-context ExoPlayer).
 *
 * Phase 21 (feedback round 4): TRUE fullscreen while a video surface is shown —
 * status + navigation bars hidden (WindowInsetsControllerCompat,
 * BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE), content drawn edge-to-edge behind system
 * bars, resize-mode toggle (default ZOOM = fill portrait viewport by cropping;
 * FIT = letterbox). Back/Finish/timer live in a tap-to-reveal overlay. Portrait stays
 * locked by design. All immersive flags restored on dispose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    videoId: String,
    onNavigateBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.factory(videoId),
        key = videoId,
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Phase 21 fullscreen state (ViewModel-owned player, composable-owned UI flags).
    var immersive by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(true) }
    var controlsVisible by remember { mutableStateOf(false) }

    val surfaceReady = !state.isFinished &&
        state.resolution != null &&
        state.resolution !is PlaybackResolution.MissingFiles

    // True fullscreen: hide system bars + draw behind them while the video surface is
    // up and immersive mode is on. Restore the previous state on dispose / exit.
    DisposableEffect(immersive, surfaceReady) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (window != null && immersive && surfaceReady) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            // Always restore, even if we exit immersive while staying on screen.
            window?.let { WindowCompat.setDecorFitsSystemWindows(it, true) }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Confirm-on-back: never lose tracked time silently (task spec §3). ≤ 30 s exits directly.
    var showExitDialog by remember { mutableStateOf(false) }
    val requestBack = {
        if (state.isFinished || viewModel.discardIfBelowThreshold()) {
            onNavigateBack()
        } else {
            showExitDialog = true
        }
    }
    BackHandler(enabled = true) {
        if (immersive) {
            // First back press leaves fullscreen; second goes through the exit flow.
            immersive = false
        } else {
            requestBack()
        }
    }

    // KeepScreenOn while playing (task spec §2); flag cleared as soon as playback pauses.
    DisposableEffect(state.isPlaying) {
        val window = (context as? Activity)?.window
        if (state.isPlaying) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Discarded (≤ 30 s back-out): row removed, leave immediately.
    LaunchedEffect(state.finishState) {
        if (state.finishState is SessionFinishState.Discarded) onNavigateBack()
    }

    if (immersive && surfaceReady) {
        // ---------- TRUE FULLSCREEN (Phase 21, FB26 UX rework) ----------
        // FB26 decision: tap on the video = play/pause TOGGLE. Controls reveal
        // on the same tap and auto-hide after ~3 s while playing (they stay up
        // while paused or while the user drags the seek bar).
        LaunchedEffect(controlsVisible, state.isPlaying) {
            if (controlsVisible && state.isPlaying) {
                delay(3_000)
                controlsVisible = false
            }
        }
        FullscreenPlayerSurface(
            viewModel = viewModel,
            state = state,
            zoom = zoom,
            controlsVisible = controlsVisible,
            onTapSurface = {
                viewModel.togglePlayPause()
                controlsVisible = true
            },
            onToggleResize = { zoom = !zoom },
            onExitFullscreen = { immersive = false },
            onBack = requestBack,
            onExitDialog = { showExitDialog = true },
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = state.video?.title ?: stringResource(R.string.player_loading),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatTimer(state.elapsedSeconds),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { requestBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.player_back),
                            )
                        }
                    },
                    actions = {
                        if (surfaceReady) {
                            IconButton(onClick = { immersive = true }) {
                                Icon(
                                    Icons.Filled.FullscreenExit,
                                    contentDescription = stringResource(R.string.video_detail_maximize),
                                )
                            }
                        }
                    },
                )
            },
        ) { padding ->
            when {
                state.finishState is SessionFinishState.AwaitingConfirm -> {
                    ConfirmStretchCard(
                        minutes = (state.finishState as SessionFinishState.AwaitingConfirm).creditedMinutes,
                        onConfirm = viewModel::confirmPending,
                        onNotNow = viewModel::leavePending,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                    )
                }

                state.finishState is SessionFinishState.Finished -> {
                    FinishedView(
                        feedback = (state.finishState as SessionFinishState.Finished).feedback,
                        onBack = onNavigateBack,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(24.dp),
                    )
                }

                state.resolution == null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }

                state.resolution is PlaybackResolution.MissingFiles -> {
                    MissingFilesView(
                        videoTitle = state.video?.title.orEmpty(),
                        onBack = onNavigateBack, // Library offers the Retry action (Phase 3)
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                    )
                }

                else -> PlayerSurface(viewModel, state, Modifier.fillMaxSize().padding(padding))
            }
        }
    }

    if (showExitDialog) {
        ExitConfirmDialog(
            onSaveAndLeave = {
                showExitDialog = false
                viewModel.finish(userPressedFinish = false)
                onNavigateBack()
            },
            onCancel = { showExitDialog = false },
        )
    }
}

/** FB27: which half a double tap landed on — seek back (left) or forward (right). */
private enum class SeekDirection { Back, Forward }

/**
 * Phase 21: edge-to-edge portrait viewport. PlayerView fills the whole screen
 * (RESIZE_MODE_ZOOM crops a 16:9 video to fill; FIT letterboxes). The stock
 * PlayerView controller is disabled; our overlay carries back / timer / resize /
 * Finish, revealed on tap.
 */
@Composable
private fun FullscreenPlayerSurface(
    viewModel: PlayerViewModel,
    state: PlayerViewModel.UiState,
    zoom: Boolean,
    controlsVisible: Boolean,
    onTapSurface: () -> Unit,
    onToggleResize: () -> Unit,
    onExitFullscreen: () -> Unit,
    onBack: () -> Unit,
    onExitDialog: () -> Unit,
) {
    // FB27: transient double-tap seek badge (auto-hides via LaunchedEffect below).
    var seekDirection by remember { mutableStateOf<SeekDirection?>(null) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = viewModel.player
                }
            },
            update = { view ->
                view.resizeMode = if (zoom) {
                    AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                } else {
                    AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // FB27: YouTube-style taps — single = play/pause + controls reveal
                    // (delayed ~300 ms to disambiguate from a double tap), double =
                    // seek ±10 s from the tapped half (left back / right forward).
                    detectTapGestures(
                        onTap = { onTapSurface() },
                        onDoubleTap = { offset ->
                            val forward = offset.x > size.width / 2f
                            viewModel.seekBy(if (forward) 10_000L else -10_000L)
                            viewModel.refreshPosition()
                            seekDirection = if (forward) {
                                SeekDirection.Forward
                            } else {
                                SeekDirection.Back
                            }
                        },
                    )
                },
        )

        // FB26: live position for the seek bar (~2 Hz; ExoPlayer has no position flow).
        LaunchedEffect(Unit) {
            while (true) {
                viewModel.refreshPosition()
                delay(500)
            }
        }

        // FB27: hide the double-tap seek badge shortly after it appears.
        LaunchedEffect(seekDirection) {
            if (seekDirection != null) {
                delay(700)
                seekDirection = null
            }
        }

        // Small transparent timer card: session timer + timer pause/play toggle.
        // Always visible (even when controls are hidden). Pausing here pauses BOTH
        // the timer and the video; playing again resumes ONLY the timer — video
        // playback is resumed by tapping the video itself.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 88.dp, end = 16.dp)
                .background(Color.Black.copy(alpha = 0.4f), MaterialTheme.shapes.large)
                .padding(horizontal = 4.dp),
        ) {
            Text(
                text = formatTimer(state.elapsedSeconds),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
            IconButton(onClick = viewModel::toggleTimer) {
                Icon(
                    imageVector = if (state.timerPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = stringResource(
                        if (state.timerPaused) R.string.player_resume_timer
                        else R.string.player_pause_timer,
                    ),
                    tint = Color.White,
                )
            }
        }

        if (controlsVisible) {
            val scrim = Modifier.background(Color.Black.copy(alpha = 0.55f))
            // Top: back + timer (system bars are hidden; pad generously for gestures).
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = scrim
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 24.dp),
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.player_back),
                        tint = Color.White,
                    )
                }
                Column {
                    Text(
                        text = state.video?.title.orEmpty(),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatTimer(state.elapsedSeconds),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.weight(1f))
                // Resize toggle: ZOOM (fill, crop) ⇄ FIT (letterbox).
                IconButton(onClick = onToggleResize) {
                    Icon(
                        Icons.Filled.AspectRatio,
                        contentDescription = stringResource(
                            if (zoom) R.string.player_resize_fit else R.string.player_resize_zoom,
                        ),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = onExitFullscreen) {
                    Icon(
                        Icons.Filled.FullscreenExit,
                        contentDescription = stringResource(R.string.player_back_to_library),
                        tint = Color.White,
                    )
                }
            }

            // Bottom: FB26 seek bar (current time / duration, draggable) + play/
            // pause toggle + Finish Session — all one tap away while immersive.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = scrim
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 32.dp, top = 8.dp),
            ) {
                var scrubbing by remember { mutableStateOf(false) }
                var scrubPositionMs by remember { mutableStateOf(0L) }
                val durationMs = state.durationMs.coerceAtLeast(0)
                val sliderValue = if (scrubbing) {
                    scrubPositionMs
                } else {
                    state.positionMs
                }
                if (durationMs > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = formatMs(sliderValue),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(48.dp),
                        )
                        Slider(
                            value = sliderValue.toFloat(),
                            onValueChange = { v ->
                                scrubbing = true
                                scrubPositionMs = v.toLong()
                            },
                            onValueChangeFinished = {
                                viewModel.seekTo(scrubPositionMs)
                                scrubbing = false
                            },
                            valueRange = 0f..durationMs.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = formatMs(durationMs),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(48.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = viewModel::togglePlayPause) {
                        Icon(
                            imageVector = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = stringResource(
                                if (state.isPlaying) R.string.player_state_playing
                                else R.string.player_state_paused,
                            ),
                            tint = Color.White,
                        )
                    }
                    Button(onClick = { viewModel.finish(userPressedFinish = true) }) {
                        Text(stringResource(R.string.player_finish))
                    }
                    Spacer(Modifier.width(48.dp)) // balances the play/pause icon
                }
                // Session timer lives in the always-visible timer card (top-right).
            }
        }

        if (!controlsVisible && !state.isPlaying && state.resolution != null) {
            Text(
                text = stringResource(R.string.player_show_controls),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            )
        }

        // FB27: double-tap seek feedback — YouTube-style badge on the tapped half.
        seekDirection?.let { dir ->
            Icon(
                imageVector = if (dir == SeekDirection.Forward) {
                    Icons.Filled.Forward10
                } else {
                    Icons.Filled.Replay10
                },
                contentDescription = stringResource(
                    if (dir == SeekDirection.Forward) {
                        R.string.player_seek_forward
                    } else {
                        R.string.player_seek_back
                    },
                ),
                tint = Color.White,
                modifier = Modifier
                    .align(
                        if (dir == SeekDirection.Forward) Alignment.CenterEnd
                        else Alignment.CenterStart,
                    )
                    .padding(horizontal = 24.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(12.dp),
            )
        }
    }
}

@Composable
private fun PlayerSurface(
    viewModel: PlayerViewModel,
    state: PlayerViewModel.UiState,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Rotation-safe: the ExoPlayer instance is owned by the ViewModel; only the
        // AndroidView surface is recreated on configuration change.
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    player = viewModel.player
                }
            },
            onRelease = { view -> view.player = null },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = state.video?.title.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(
                    if (state.isPlaying) R.string.player_state_playing else R.string.player_state_paused,
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            // Prominent elapsed timer — counts only while playing.
            Text(
                text = formatTimer(state.elapsedSeconds),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = stringResource(R.string.player_timer_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { viewModel.finish(userPressedFinish = true) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.player_finish))
            }
        }
    }
}

@Composable
private fun MissingFilesView(
    videoTitle: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🐢", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.player_missing_files),
            style = MaterialTheme.typography.titleMedium,
        )
        if (videoTitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = videoTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onBack) {
            Text(stringResource(R.string.player_retry_download))
        }
    }
}

/**
 * Phase 22: the explicit stretch-confirmation card. Playback alone records nothing —
 * the octopus only counts stretches the user actually did.
 */
@Composable
private fun ConfirmStretchCard(
    minutes: Int,
    onConfirm: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🐙", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.player_confirm_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.player_confirm_body, minutes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.player_confirm_yes))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onNotNow) {
            Text(stringResource(R.string.player_confirm_not_now))
        }
    }
}

@Composable
private fun FinishedView(
    feedback: TailFeedback?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.player_finished_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (feedback == null) {
                stringResource(R.string.player_pending_saved)
            } else {
                when (feedback.result) {
                    TailIntegrationClient.SendResult.SENT ->
                        stringResource(R.string.player_tail_sent, feedback.minutes, feedback.habitName)
                    TailIntegrationClient.SendResult.NO_TAIL ->
                        stringResource(R.string.player_tail_not_installed)
                    TailIntegrationClient.SendResult.ERROR ->
                        stringResource(R.string.player_tail_error)
                }
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text(stringResource(R.string.player_back_to_library)) }
    }
}

@Composable
private fun ExitConfirmDialog(
    onSaveAndLeave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.player_exit_title)) },
        text = { Text(stringResource(R.string.player_exit_body)) },
        confirmButton = {
            TextButton(onClick = onSaveAndLeave) {
                Text(stringResource(R.string.player_exit_save_and_leave))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.player_cancel)) }
        },
    )
}

/** mm:ss (under an hour) / h:mm:ss. */
private fun formatTimer(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/** FB26 seek-bar label: m:ss (h:mm:ss past an hour). */
private fun formatMs(ms: Long): String = formatTimer((ms / 1000).toInt())
