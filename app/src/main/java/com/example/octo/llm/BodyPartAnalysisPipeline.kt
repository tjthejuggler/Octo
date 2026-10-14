package com.example.octo.llm

import android.util.Log
import com.example.octo.data.local.AnalysisState
import com.example.octo.data.local.BodyPart
import com.example.octo.data.local.Muscle
import com.example.octo.data.local.PartSource
import com.example.octo.data.local.OctoDatabase
import com.example.octo.data.local.TranscriptSource
import com.example.octo.data.settings.OctoSettings
import com.example.octo.data.settings.SettingsRepository
import androidx.room.withTransaction
import com.example.octo.youtube.TranscriptFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 5 orchestration (task spec §3): for one video —
 *
 * 1. fetch the transcript if the row doesn't have one ([TranscriptFetcher] fallback chain)
 * 2. classify into body-part ids: LLM when an API key is configured, else [LlmHeuristics]
 * 3. muscle detail layer (LLM only): classify the exact stretched muscles from
 *    video frames (vision) + transcript, and roll them UP into the body-part
 *    categories so the 16-part system stays the single source of truth
 * 4. atomically replace [com.example.octo.data.local.VideoBodyPartCrossRef] and
 *    [com.example.octo.data.local.VideoMuscleCrossRef] rows
 * 5. mark [AnalysisState.DONE] (or FAILED with transcript left null when unobtainable)
 *
 * `ensureAnalyzed` is idempotent (no-op when already DONE) and re-entrant-safe via the
 * RUNNING marker — a second trigger while RUNNING returns without duplicating work.
 */
class BodyPartAnalysisPipeline(
    private val db: OctoDatabase,
    private val transcriptFetcher: TranscriptFetcher,
    private val llmClient: LlmClient,
    private val settingsRepository: SettingsRepository,
) {

    /** Outcome for logging / UI toasts. */
    enum class Outcome { ANALYZED, ALREADY_DONE, SKIPPED_RUNNING, NO_TRANSCRIPT, FAILED }

    /**
     * Runs the full pipeline for [videoId] on [Dispatchers.IO].
     * Never throws — all failures collapse into `FAILED` state on the row.
     */
    suspend fun ensureAnalyzed(videoId: String, force: Boolean = false): Outcome =
        withContext(Dispatchers.IO) {
            val dao = db.videoDao()
            val row = dao.getById(videoId) ?: return@withContext Outcome.FAILED

            if (!force && row.analysisState == AnalysisState.DONE) {
                return@withContext Outcome.ALREADY_DONE
            }
            // force=true (Reanalyze library) pushes through a stale RUNNING marker left
            // by a process-killed run; only opportunistic triggers respect the lock.
            if (!force && row.analysisState == AnalysisState.RUNNING) {
                return@withContext Outcome.SKIPPED_RUNNING
            }

            dao.markAnalysisRunning(videoId)
            try {
                // ── 1. transcript ──
                var transcript = row.transcriptText
                var source: TranscriptSource? = row.transcriptSource
                if (transcript.isNullOrBlank()) {
                    val fetched = transcriptFetcher.fetch(videoId)
                    if (fetched == null) {
                        dao.updateAnalysisState(videoId, AnalysisState.FAILED, now())
                        return@withContext Outcome.NO_TRANSCRIPT
                    }
                    transcript = fetched.text
                    source = fetched.source
                    dao.updateTranscript(videoId, transcript, source)
                }

                // ── 2. classification (LLM if configured, else heuristics) ──
                val settings: OctoSettings = settingsRepository.settings.first()
                val llmConfigured = settings.llmApiKey.isNotBlank()
                val endpoint = if (llmConfigured) {
                    LlmClient.Endpoint(
                        baseUrl = settings.llmBaseUrl.ifBlank { SettingsRepository.DEFAULT_LLM_BASE_URL },
                        apiKey = settings.llmApiKey,
                        model = settings.llmModel.ifBlank { SettingsRepository.DEFAULT_LLM_MODEL },
                    )
                } else {
                    null
                }

                val bodyPartAnalysis = if (endpoint != null) {
                    llmClient.classifyBodyParts(
                        endpoint = endpoint,
                        transcript = transcript,
                        isTitleDescriptionOnly = source == TranscriptSource.TITLE_DESCRIPTION,
                    )
                } else {
                    LlmHeuristics.classify(transcript)
                }

                // Normalize: map to the enum (drops anything unknown), keep canonical order.
                val bodyParts = bodyPartAnalysis.bodyParts
                    .mapNotNull { BodyPart.fromId(it) }
                    .toSet()
                    .toMutableSet()

                // ── 3. muscle detail layer (LLM only; vision when frames available) ──
                // A muscle-analysis failure is NOT fatal: the video keeps its
                // body-part result and simply has no muscle detail yet.
                val muscles = runCatching {
                    if (endpoint != null) extractMuscles(endpoint, row.localFilePath, transcript)
                    else emptyList()
                }.getOrElse { t ->
                    Log.w(TAG, "Muscle analysis failed for $videoId (non-fatal)", t)
                    emptyList()
                }

                // Roll up: every identified muscle credits its parent body part
                // category — the 16-part system stays the single source of truth.
                muscles.forEach { bodyParts += it.parent }

                // ── 4. + 5. atomic link replace + state flip ──
                // Phase 24: provenance recorded so the UI can show "AI-analyzed"
                // vs "Quick scan" per video. NOTE: a (re)analysis intentionally
                // OVERWRITES a MANUAL selection — the Library shows a confirm
                // dialog when partSource == MANUAL for that reason.
                val provenance = if (llmConfigured) {
                    PartSource.LLM
                } else {
                    PartSource.HEURISTIC
                }
                db.withTransaction {
                    db.videoBodyPartDao().replaceForVideo(videoId, bodyParts.map { it.id })
                    db.videoMuscleDao().replaceForVideo(videoId, muscles.map { it.id })
                    dao.updateAnalysisState(videoId, AnalysisState.DONE, now())
                    dao.updatePartSource(videoId, provenance)
                }
                Log.i(
                    TAG,
                    "Analyzed %s: %d parts, %d muscles (%s) via %s".format(
                        videoId,
                        bodyParts.size,
                        muscles.size,
                        muscles.joinToString { it.id },
                        if (llmConfigured) "llm" else "heuristic",
                    ),
                )
                Outcome.ANALYZED
            } catch (t: Throwable) {
                Log.w(TAG, "Analysis failed for $videoId", t)
                try {
                    dao.updateAnalysisState(videoId, AnalysisState.FAILED, now())
                } catch (_: Exception) {
                    // row deleted mid-flight — nothing to mark
                }
                Outcome.FAILED
            }
        }

    /** All library video ids (newest first) — the reanalyze worker's job list. */
    suspend fun allVideoIds(): List<String> = withContext(Dispatchers.IO) {
        db.videoDao().getAllOnce().map { it.id }
    }

    /** One vision call with sampled frames; falls back to text-only when no local file. */
    private fun extractMuscles(
        endpoint: LlmClient.Endpoint,
        localFilePath: String?,
        transcript: String?,
    ): List<Muscle> {
        val frames = localFilePath
            ?.let { File(it) }
            ?.takeIf { it.isFile }
            ?.let { VideoFrameExtractor.extractBase64Frames(it) }
            .orEmpty()
        Log.i(TAG, "Muscle analysis: ${frames.size} frames extracted")
        val analysis = llmClient.classifyMuscles(endpoint, transcript, frames)
        return analysis.muscles.mapNotNull { Muscle.fromId(it) }.distinct()
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val TAG = "BodyPartPipeline"
    }
}
