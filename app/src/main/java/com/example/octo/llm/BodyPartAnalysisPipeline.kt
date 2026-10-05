package com.example.octo.llm

import android.util.Log
import com.example.octo.data.local.AnalysisState
import com.example.octo.data.local.BodyPart
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

/**
 * Phase 5 orchestration (task spec §3): for one video —
 *
 * 1. fetch the transcript if the row doesn't have one ([TranscriptFetcher] fallback chain)
 * 2. classify into body-part ids: LLM when an API key is configured, else [LlmHeuristics]
 * 3. atomically replace [com.example.octo.data.local.VideoBodyPartCrossRef] rows
 * 4. mark [AnalysisState.DONE] (or FAILED with transcript left null when unobtainable)
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
            if (row.analysisState == AnalysisState.RUNNING) {
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
                val analysis = if (settings.llmApiKey.isNotBlank()) {
                    llmClient.classifyBodyParts(
                        endpoint = LlmClient.Endpoint(
                            baseUrl = settings.llmBaseUrl.ifBlank { SettingsRepository.DEFAULT_LLM_BASE_URL },
                            apiKey = settings.llmApiKey,
                            model = settings.llmModel.ifBlank { SettingsRepository.DEFAULT_LLM_MODEL },
                        ),
                        transcript = transcript,
                        isTitleDescriptionOnly = source == TranscriptSource.TITLE_DESCRIPTION,
                    )
                } else {
                    LlmHeuristics.classify(transcript)
                }

                // Normalize: map to the enum (drops anything unknown), keep canonical order.
                val bodyParts = analysis.bodyParts
                    .mapNotNull { BodyPart.fromId(it) }
                    .toSet()

                // ── 3. + 4. atomic link replace + state flip ──
                // Phase 24: provenance recorded so the UI can show "AI-analyzed"
                // vs "Quick scan" per video. NOTE: a (re)analysis intentionally
                // OVERWRITES a MANUAL selection — the Library shows a confirm
                // dialog when partSource == MANUAL for that reason.
                val provenance = if (settings.llmApiKey.isNotBlank()) {
                    PartSource.LLM
                } else {
                    PartSource.HEURISTIC
                }
                db.withTransaction {
                    db.videoBodyPartDao().replaceForVideo(videoId, bodyParts.map { it.id })
                    dao.updateAnalysisState(videoId, AnalysisState.DONE, now())
                    dao.updatePartSource(videoId, provenance)
                }
                Log.i(
                    TAG,
                    "Analyzed %s: %d parts (%s) via %s".format(
                        videoId,
                        bodyParts.size,
                        bodyParts.joinToString { it.id },
                        if (settings.llmApiKey.isNotBlank()) "llm" else "heuristic",
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

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val TAG = "BodyPartPipeline"
    }
}
