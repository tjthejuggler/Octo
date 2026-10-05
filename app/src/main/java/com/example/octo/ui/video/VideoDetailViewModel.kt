package com.example.octo.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.octo.OctoApplication
import com.example.octo.data.local.AnalysisState
import com.example.octo.data.local.BodyPart
import com.example.octo.data.local.PartSource
import com.example.octo.data.local.BodyPartDao
import com.example.octo.data.local.SessionDao
import com.example.octo.data.local.SessionWithTitle
import com.example.octo.data.local.VideoDao
import com.example.octo.data.local.VideoEntity
import com.example.octo.data.repository.SessionRepository
import com.example.octo.data.repository.VideoRepository
import com.example.octo.data.settings.SettingsRepository
import com.example.octo.ipc.TailIntegrationClient
import com.example.octo.player.PlaybackResolution
import com.example.octo.player.SessionPlayerManager
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 12 — presentation state for the video detail screen.
 *
 * @property video the library row (live; null while loading / unknown id).
 * @property bodyParts parts this video stretches — only populated once the Phase 5
 *  pipeline reached [AnalysisState.DONE] (cross-ref rows are only meaningful then).
 * @property todayMinutes today's per-part stretch minutes (same source as the dashboard).
 * @property resolution local playback resolution; null until computed.
 */
data class VideoDetailUiState(
    val video: VideoEntity? = null,
    val bodyParts: List<BodyPart> = emptyList(),
    val todayMinutes: Map<BodyPart, Int> = emptyMap(),
    val resolution: PlaybackResolution? = null,
    /** Phase 22: pending (played, unconfirmed) sessions for THIS video, newest first. */
    val pendingSessions: List<SessionWithTitle> = emptyList(),
) {
    val isAnalyzing: Boolean
        get() = video?.analysisState == AnalysisState.RUNNING

    val isAnalysisDone: Boolean
        get() = video?.analysisState == AnalysisState.DONE

    /**
     * Phase 24: how the current links were produced — drives the source chip.
     * Legacy pre-v4 rows (DONE, partSource NULL) derive HEURISTIC so the chip
     * renders for every analyzed video (the old pipeline always finished via
     * the heuristic/LLM classify path; this app's default has no API key).
     */
    val partSource: PartSource?
        get() = video?.partSource
            ?: PartSource.HEURISTIC.takeIf { isAnalysisDone }

    /** True right after the user saved an "Edit parts" selection in this session. */
    val manualEdited: Boolean
        get() = video?.partSource == PartSource.MANUAL

    /** Amber target set = parts this video stretches that are NOT yet stretched today. */
    val videoTargets: Set<BodyPart>
        get() = if (isAnalysisDone) {
            bodyParts.filter { (todayMinutes[it] ?: 0) <= 0 }.toSet()
        } else {
            emptySet()
        }
}

/**
 * Phase 12 — Video detail ("video/{videoId}").
 *
 * Phase 26 (FB26): no player instance here anymore — the poster's single tap
 * hands playback to the fullscreen PlayerScreen route (which creates its own
 * ExoPlayer and auto-plays). This ViewModel is pure state: video row, parts,
 * today minutes, pending sessions.
 */
class VideoDetailViewModel(
    private val videoId: String,
    private val videoDao: VideoDao,
    bodyPartDao: BodyPartDao,
    private val videoRepository: VideoRepository,
    private val playerManager: SessionPlayerManager,
    private val sessionRepository: SessionRepository,
    sessionDao: SessionDao,
    private val settingsRepository: SettingsRepository,
    private val tailClient: TailIntegrationClient,
) : ViewModel() {

    val uiState: StateFlow<VideoDetailUiState> = combine(
        videoDao.observeWithBodyPartsById(videoId),
        bodyPartDao.forDay(LocalDate.now().iso()),
        sessionDao.pendingForVideo(videoId),
    ) { row, dayRows, pending ->
        val video = row?.video
        // Cross-ref links only count once analysis finished (empty DONE is valid).
        val parts = if (video?.analysisState == AnalysisState.DONE) {
            row.bodyParts.mapNotNull { BodyPart.fromId(it.bodyPartId) }.sortedBy { it.ordinal }
        } else {
            emptyList()
        }
        VideoDetailUiState(
        video = video,
        bodyParts = parts,
        todayMinutes = dayRows.mapNotNull { r ->
            BodyPart.fromId(r.bodyPartId)?.takeIf { r.minutes > 0 }?.to(r.minutes)
        }.toMap(),
        resolution = video?.let(playerManager::resolve),
        pendingSessions = pending,
    )
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VideoDetailUiState())

    /**
     * FB26: the video detail screen no longer hosts an ExoPlayer — the poster's
     * single tap hands playback to the fullscreen PlayerScreen (which auto-plays).
     * Kept as a cheap no-op so screen-level dispose stays a one-liner.
     */
    fun releaseMiniPlayer() = Unit

    fun retryDownload() {
        viewModelScope.launch { videoRepository.retryDownload(videoId) }
    }

    // ── Phase 24: "Edit parts" — user-maintained body-part links ──

    /**
     * Persists the user's selection (one Room transaction: replace links + DONE +
     * MANUAL stamp). Confirmed sessions keep their credited snapshots — undo stays
     * exact; only FUTURE confirmations credit the new set.
     */
    fun saveManualParts(parts: Set<BodyPart>) {
        viewModelScope.launch {
            videoRepository.applyManualParts(videoId, parts.map { it.id })
        }
    }

    // ── Phase 22: pending-session banner actions ──

    /** "Log it": explicit confirmation — aggregates credited on the session's start date + Tail. */
    fun confirmPendingSession(sessionRowId: Long) {
        viewModelScope.launch {
            val minutes = sessionRepository.confirmSession(sessionRowId) ?: return@launch
            if (minutes > 0) {
                val habit = settingsRepository.settings.firstOrNull()?.tailHabitName
                    ?.takeIf { it.isNotBlank() }
                    ?: SettingsRepository.DEFAULT_TAIL_HABIT_NAME
                tailClient.sendIncrement(habitName = habit, minutes = minutes, sessions = 1)
            }
        }
    }

    /** "Discard": deletes the pending row — nothing was recorded, so nothing to reverse. */
    fun discardPendingSession(sessionRowId: Long) {
        viewModelScope.launch { sessionRepository.discardSession(sessionRowId) }
    }

    override fun onCleared() {
        releaseMiniPlayer()
        super.onCleared()
    }

    private fun LocalDate.iso(): String = format(DateTimeFormatter.ISO_LOCAL_DATE)

    companion object {
        /** Factory pattern consistent with the other screens (manual DI, no Hilt). */
        fun factory(videoId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OctoApplication
                val container = app.appContainer
                VideoDetailViewModel(
                    videoId = videoId,
                    videoDao = container.database.videoDao(),
                    bodyPartDao = container.database.bodyPartDao(),
                    videoRepository = container.videoRepository,
                    playerManager = container.sessionPlayerManager,
                    sessionRepository = container.sessionRepository,
                    sessionDao = container.database.sessionDao(),
                    settingsRepository = container.settingsRepository,
                    tailClient = TailIntegrationClient(app),
                )
            }
        }
    }
}
