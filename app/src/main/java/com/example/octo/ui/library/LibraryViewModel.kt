package com.example.octo.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.octo.OctoApplication
import com.example.octo.data.local.AnalysisState
import com.example.octo.data.local.BodyPart
import com.example.octo.data.local.DownloadState
import com.example.octo.data.local.VideoEntity
import com.example.octo.work.DownloadVideoWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Library presentation state: DB rows merged with live WorkManager progress so the
 * "Downloading…" chips show real byte progress without polling the DB.
 */
class LibraryViewModel(
    private val videoRepository: com.example.octo.data.repository.VideoRepository,
    workManager: WorkManager,
) : ViewModel() {

    /** Per-video live download progress, keyed by videoId. */
    data class DownloadProgress(
        val workState: WorkInfo.State?,
        val fraction: Float?, // null → indeterminate
    )

    data class VideoUiItem(
        val video: VideoEntity,
        val progress: DownloadProgress? = null,
        val bodyParts: List<BodyPart> = emptyList(),
    ) {
        /**
         * Phase 24: how the current links were made. Legacy pre-v4 rows (DONE but
         * partSource NULL) derive HEURISTIC so the provenance tag actually renders
         * for every analyzed video — the old pipeline only finished via the
         * heuristic/LLM classify path, and this app's shipped default is no API
         * key (heuristic). Library "Analyze" re-runs the pipeline and stamps the
         * definitive value.
         */
        val partSource: com.example.octo.data.local.PartSource?
            get() = video.partSource
                ?: com.example.octo.data.local.PartSource.HEURISTIC
                    .takeIf { video.analysisState == AnalysisState.DONE }
        val isDownloading: Boolean
            get() = progress?.workState == WorkInfo.State.RUNNING ||
                video.downloadState == DownloadState.DOWNLOADING

        val isFailed: Boolean
            get() = video.downloadState == DownloadState.FAILED && progress == null

        val isPlayable: Boolean
            get() = video.downloadState == DownloadState.COMPLETED && video.localFilePath != null

        /** Spinner chip while the Phase 5 pipeline runs. */
        val isAnalyzing: Boolean
            get() = video.analysisState == AnalysisState.RUNNING

        /** Analyze menu entry visible when the pipeline never finished (NONE/FAILED),
         *  or when the row is DONE with a MANUAL selection (Phase 24: the menu entry
         *  then triggers a confirm dialog — re-analysis replaces the manual set),
         *  or when the row is DONE with legacy NULL provenance (pre-v4 rows:
         *  re-analyzing stamps the definitive partSource). */
        val canAnalyze: Boolean
            get() = video.analysisState == AnalysisState.NONE ||
                video.analysisState == AnalysisState.FAILED ||
                (video.analysisState == AnalysisState.DONE &&
                    (video.partSource == com.example.octo.data.local.PartSource.MANUAL ||
                        video.partSource == null))

        /**
         * Phase 24: DONE + stored MANUAL (raw column, NOT the derived value) —
         * "Analyze" needs the replace-edits confirm dialog. A legacy NULL row is
         * NOT "manual", so it re-analyzes without the warning.
         */
        val needsManualConfirmBeforeAnalyze: Boolean
            get() = video.analysisState == AnalysisState.DONE &&
                video.partSource == com.example.octo.data.local.PartSource.MANUAL
    }

    private val workInfos: Flow<List<WorkInfo>> =
        workManager.getWorkInfosByTagFlow(DownloadVideoWorker.UNIQUE_WORK_NAME_PREFIX)

    val items: StateFlow<List<VideoUiItem>> =
        combine(videoRepository.library, workInfos) { rows, infos ->
            val activeInfos = infos.filter { it.state in ACTIVE_STATES }

            rows.map { row ->
                val info = activeInfos.firstOrNull { row.video.id in it.tags }
                val progress = if (info != null) {
                    DownloadProgress(workState = info.state, fraction = progressFraction(info))
                } else {
                    null
                }
                val parts = row.bodyParts
                    .mapNotNull { BodyPart.fromId(it.bodyPartId) }
                    .sortedBy { it.ordinal }
                VideoUiItem(video = row.video, progress = progress, bodyParts = parts)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- Phase 21 (feedback round 4): client-side search + body-part filter ----
    // State lives in the ViewModel so filters survive navigating away and back.

    /** Free-text query matched (case-insensitive contains) against title + channel. */
    private val searchQuery = MutableStateFlow("")

    /** Selected body parts — multi-select with OR semantics (video matches ANY). */
    private val selectedParts = MutableStateFlow<Set<BodyPart>>(emptySet())

    val query: StateFlow<String> = searchQuery.asStateFlow()
    val parts: StateFlow<Set<BodyPart>> = selectedParts.asStateFlow()

    /**
     * Filtered view of [items]: text filter AND part filter (OR within selected parts).
     * Computed reactively over the existing Room flow — no new DAO needed.
     */
    val filteredItems: StateFlow<List<VideoUiItem>> =
        combine(items, searchQuery, selectedParts) { rows, query, parts ->
            val q = query.trim()
            rows.filter { item ->
                val textMatch = q.isEmpty() ||
                    item.video.title.contains(q, ignoreCase = true) ||
                    item.video.channel.contains(q, ignoreCase = true)
                val partMatch = parts.isEmpty() || item.bodyParts.any { it in parts }
                textMatch && partMatch
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        searchQuery.value = value
    }

    /** Toggles a part in the multi-select filter set (OR semantics within the set). */
    fun togglePart(part: BodyPart) {
        selectedParts.value = selectedParts.value.toMutableSet().apply {
            if (!add(part)) remove(part)
        }
    }

    fun clearFilters() {
        searchQuery.value = ""
        selectedParts.value = emptySet()
    }

    fun addUrl(text: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            onResult(videoRepository.addSharedUrl(text) != null)
        }
    }

    fun retry(videoId: String) {
        viewModelScope.launch { videoRepository.retryDownload(videoId) }
    }

    /** Manual "Analyze" (card menu, task spec §4): transcript + extraction, force=false so
     *  already-DONE rows are untouched; FAILED rows are retried by this call.
     *  Phase 24: force=true variant used by the manual-edits confirm dialog —
     *  re-analysis intentionally OVERWRITES a MANUAL selection. */
    fun analyze(videoId: String) {
        viewModelScope.launch {
            runCatching { videoRepository.analyzeVideo(videoId) }
        }
    }

    fun analyzeForced(videoId: String) {
        viewModelScope.launch {
            runCatching { videoRepository.analyzeVideo(videoId, force = true) }
        }
    }

    fun delete(videoId: String) {
        viewModelScope.launch { videoRepository.deleteVideo(videoId) }
    }

    private fun progressFraction(info: WorkInfo): Float? {
        val read = info.progress.getLong(DownloadVideoWorker.PROGRESS_BYTES, -1L)
        val total = info.progress.getLong(DownloadVideoWorker.PROGRESS_TOTAL, -1L)
        return if (read >= 0 && total > 0) (read.toDouble() / total).toFloat().coerceIn(0f, 1f) else null
    }

    private val ACTIVE_STATES =
        setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as OctoApplication
                LibraryViewModel(
                    videoRepository = app.appContainer.videoRepository,
                    workManager = WorkManager.getInstance(app),
                )
            }
        }
    }
}
