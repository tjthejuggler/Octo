package com.example.octo.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.octo.OctoApplication
import com.example.octo.data.local.VideoEntity
import com.example.octo.data.repository.SessionRepository
import com.example.octo.data.settings.OctoSettings
import com.example.octo.data.settings.SettingsRepository
import com.example.octo.ipc.TailIntegrationClient
import com.example.octo.player.PlaybackResolution
import com.example.octo.player.SessionPlayerManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Session lifecycle state — drives the UI's finish/discard paths. */
sealed interface SessionFinishState {
    /** Player screen active, timer armed. */
    data object Active : SessionFinishState

    /**
     * Phase 22: playback finished, session saved PENDING. Nothing is recorded yet —
     * the octopus only counts stretches the user explicitly confirms. [creditedMinutes]
     * is the preview for the confirm card; [sessionRowId] identifies the pending row.
     */
    data class AwaitingConfirm(val creditedMinutes: Int, val sessionRowId: Long) : SessionFinishState

    /** Terminal: [feedback] non-null = confirmed + Tail attempted; null = left pending. */
    data class Finished(val feedback: TailFeedback?) : SessionFinishState

    /** User backed out with ≤ threshold seconds — row discarded, nothing recorded. */
    data object Discarded : SessionFinishState
}

/** Tail broadcast outcome for UI feedback ("✓ Sent 12 min to Tail" / graceful NoTail). */
data class TailFeedback(
    val result: TailIntegrationClient.SendResult,
    val minutes: Int,
    val habitName: String,
)

/**
 * Owns the ExoPlayer across rotation (application context), the play-time-only
 * session timer, and the finish pipeline:
 * persist session → daily body-part increments (one transaction) → ONE Tail broadcast.
 */
class PlayerViewModel(
    private val application: Application,
    private val videoId: String,
    private val sessionRepository: SessionRepository,
    private val settingsRepository: SettingsRepository,
    private val playerManager: SessionPlayerManager,
    private val analysisPipeline: com.example.octo.llm.BodyPartAnalysisPipeline,
) : AndroidViewModel(application) {

    data class UiState(
        val video: VideoEntity? = null,
        val settings: OctoSettings? = null,
        val elapsedSeconds: Int = 0,
        val isPlaying: Boolean = false,
        val resolution: PlaybackResolution? = null, // null until resolve ran
        val finishState: SessionFinishState = SessionFinishState.Active,
        /** FB26: live media position/duration (ms) for the fullscreen seek bar. */
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
    ) {
        val isFinished: Boolean get() = finishState !is SessionFinishState.Active
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Bound by [PlayerScreen]'s AndroidView; created lazily once the video is resolved. */
    val player: ExoPlayer
        get() = playerField ?: createAndPreparePlayer().also { playerField = it }

    private var playerField: ExoPlayer? = null

    private val tailClient = TailIntegrationClient(application)

    private var sessionRowId: Long = -1L
    private var timerJob: Job? = null

    /**
     * Set SYNCHRONOUSLY by [finish] (before any coroutine runs) so [onCleared]'s
     * safety net can never double-complete a session that finish() is still persisting
     * on viewModelScope — "Save and leave" navigates back immediately, which can tear
     * the ViewModel down while the Room transaction is in flight (Phase 8 E2E fix).
     */
    @Volatile
    private var finishHandled = false

    init {
        viewModelScope.launch {
            val video = sessionRepository.getVideo(videoId)
            if (video == null) {
                // Unknown videoId (stale route after process death) — UI shows the error state.
                _uiState.update { it.copy(resolution = PlaybackResolution.MissingFiles) }
                return@launch
            }
            // Session row opens the moment the screen appears (task spec §3).
            sessionRowId = sessionRepository.start(videoId)

            val settings = settingsRepository.settings.firstOrNull()
            _uiState.update {
                it.copy(
                    video = video,
                    settings = settings,
                    resolution = playerManager.resolve(video),
                )
            }

            // Phase 5: fire-and-forget analysis on play when not yet analyzed (task spec §4).
            // Safe to call unconditionally — ensureAnalyzed is a no-op when already DONE and
            // SKIPPED_RUNNING guards double triggers; failures land on the row as FAILED.
            runCatching {
                analysisPipeline.ensureAnalyzed(videoId)
            }
        }
    }

    private fun createAndPreparePlayer(): ExoPlayer {
        val video = requireNotNull(_uiState.value.video) { "Player created before video resolved" }
        val exo = playerManager.createPlayer(listener)
        val resolution = playerManager.resolve(video)
        if (resolution is PlaybackResolution.Ready) {
            exo.setMediaSource(resolution.source)
            exo.prepare()
            exo.playWhenReady = true
        }
        return exo
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startTimer() else stopTimer()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Natural end-of-media == session completion (task spec §3).
            if (playbackState == Player.STATE_ENDED && !_uiState.value.isFinished) {
                finish(userPressedFinish = true)
            }
        }
    }

    // ── Phase 26 (FB26): tap-to-toggle + seek bar support ──

    /** Tap on the video surface: pause ⇄ play (session timer follows isPlaying). */
    fun togglePlayPause() {
        val exo = playerField ?: return
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    /** Live media position for the seek bar; no-op before the player exists. */
    fun seekTo(positionMs: Long) {
        playerField?.seekTo(positionMs.coerceAtLeast(0))
    }

    /** True once a player instance exists (seek bar reads position from it). */
    fun hasPlayer(): Boolean = playerField != null

    /**
     * Polls the live media position/duration into [UiState] for the fullscreen
     * seek bar (UI calls this ~2 Hz — ExoPlayer has no position flow).
     */
    fun refreshPosition() {
        val exo = playerField ?: return
        _uiState.update {
            it.copy(
                positionMs = exo.currentPosition.coerceAtLeast(0),
                durationMs = exo.duration.coerceAtLeast(0),
            )
        }
    }

    /** Timer ticks only while the player reports playing (pause = rest, not counted). */
    private fun startTimer() {
        if (timerJob?.isActive == true) return
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                _uiState.update { it.copy(elapsedSeconds = it.elapsedSeconds + 1) }
            }
        }
    }

    private fun stopTimer() {
        timerJob?.cancel()
        timerJob = null
    }

    /**
     * Finish pipeline (Phase 22 rework): persists the session in the PENDING state
     * ONLY — no body-part increments, no Tail broadcast — and hands the flow to the
     * explicit confirm card ([SessionFinishState.AwaitingConfirm]). "Yes, log it" →
     * [confirmPending]; "Not now" → [leavePending]. The aggregates/Tail side effects
     * now live in [SessionRepository.confirmSession], called exactly once.
     *
     * The [finishHandled] guard is flipped synchronously so that a navigate-back racing
     * this coroutine (onCleared's safety net) can never persist the session twice
     * (Phase 8 double-complete race fix — preserved by this rework).
     */
    fun finish(userPressedFinish: Boolean) {
        if (finishHandled || _uiState.value.isFinished) return
        finishHandled = true
        stopTimer()
        // Pause only if the player was actually created (MissingFiles path never creates one).
        playerField?.pause()

        val state = _uiState.value
        val elapsed = state.elapsedSeconds
        val rowId = sessionRowId
        val creditedMinutes = sessionRepository.creditedMinutes(elapsed)
        val duration = state.video?.durationSeconds ?: 0
        val completed = userPressedFinish ||
            (duration > 0 && elapsed >= duration * COMPLETION_RATIO)

        viewModelScope.launch {
            if (rowId > 0) {
                sessionRepository.complete(
                    sessionRowId = rowId,
                    elapsedSeconds = elapsed,
                    completed = completed,
                )
            }
            _uiState.update {
                it.copy(finishState = SessionFinishState.AwaitingConfirm(creditedMinutes, rowId))
            }
        }
    }

    /** "Yes, log it": confirm the pending session (aggregates + snapshot) then send Tail. */
    fun confirmPending() {
        val finishState = _uiState.value.finishState
        if (finishState !is SessionFinishState.AwaitingConfirm) return
        val state = _uiState.value
        viewModelScope.launch {
            val minutes = sessionRepository.confirmSession(finishState.sessionRowId) ?: 0
            val feedback = sendToTail(minutes, state)
            _uiState.update { it.copy(finishState = SessionFinishState.Finished(feedback)) }
        }
    }

    /** "Not now": keep the session pending — confirmable later from Body / video detail. */
    fun leavePending() {
        if (_uiState.value.finishState is SessionFinishState.AwaitingConfirm) {
            _uiState.update { it.copy(finishState = SessionFinishState.Finished(null)) }
        }
    }

    /** Back-out with ≤ threshold seconds → discard the row; longer back-outs ask via UI confirm. */
    fun discardIfBelowThreshold(): Boolean {
        if (finishHandled) return _uiState.value.isFinished
        val elapsed = _uiState.value.elapsedSeconds
        return if (elapsed <= DISCARD_THRESHOLD_SECONDS) {
            finishHandled = true
            stopTimer()
            val rowId = sessionRowId
            if (rowId > 0) {
                viewModelScope.launch { sessionRepository.discard(rowId) }
            }
            _uiState.update { it.copy(finishState = SessionFinishState.Discarded) }
            true
        } else {
            false
        }
    }

    private fun sendToTail(minutes: Int, state: UiState): TailFeedback {
        val habit = state.settings?.tailHabitName?.takeIf { it.isNotBlank() }
            ?: SettingsRepository.DEFAULT_TAIL_HABIT_NAME
        if (minutes <= 0) {
            // Nothing credited (≤ 30 s) — no broadcast; session saved locally only.
            return TailFeedback(TailIntegrationClient.SendResult.SENT, minutes, habit)
        }
        val result = tailClient.sendIncrement(habitName = habit, minutes = minutes, sessions = 1)
        return TailFeedback(result, minutes, habit)
    }

    override fun onCleared() {
        timerJob?.cancel()
        val state = _uiState.value
        // finishHandled covers both finish() (its coroutine may still be running) and
        // discardIfBelowThreshold() — neither path may be double-applied here.
        if (!finishHandled && !state.isFinished && sessionRowId > 0 && state.elapsedSeconds > 0) {
            // Safety net (user left without Finish despite confirm-on-back): persist the
            // played time as a PENDING session on the application scope — NEVER
            // auto-confirm (Phase 22: recording requires explicit user confirmation;
            // the pending banners on Body / video detail offer the "Log it" path).
            val rowId = sessionRowId
            val elapsed = state.elapsedSeconds
            (application as OctoApplication).applicationScope.launch {
                sessionRepository.complete(
                    sessionRowId = rowId,
                    elapsedSeconds = elapsed,
                    completed = false,
                )
            }
        }
        playerField?.release()
        super.onCleared()
    }

    companion object {
        const val DISCARD_THRESHOLD_SECONDS = 30
        const val COMPLETION_RATIO = 0.8

        /** Factory pattern consistent with [com.example.octo.ui.library.LibraryViewModel.Factory]. */
        fun factory(videoId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OctoApplication
                val container = app.appContainer
                PlayerViewModel(
                    application = app,
                    videoId = videoId,
                    sessionRepository = container.sessionRepository,
                    settingsRepository = container.settingsRepository,
                    playerManager = container.sessionPlayerManager,
                    analysisPipeline = container.bodyPartAnalysisPipeline,
                )
            }
        }
    }
}
