package com.example.octo.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.octo.llm.BodyPartAnalysisPipeline

/**
 * "Reanalyze library" (Settings action): force re-runs the full analysis
 * pipeline — body parts AND the muscle detail layer (vision when frames are
 * available) — for every library video, sequentially on a background worker
 * so the app can be closed mid-run without corrupting state (each video's
 * replace is transactional; RUNNING markers keep re-entry safe).
 *
 * Progress is reported as "done/total" via setProgress for the Settings UI.
 */
class ReanalyzeLibraryWorker(
    appContext: Context,
    params: WorkerParameters,
    private val pipeline: BodyPartAnalysisPipeline,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val videoIds = pipeline.allVideoIds()
        if (videoIds.isEmpty()) return Result.success()

        Log.i(TAG, "Reanalyze library: ${videoIds.size} videos")
        var analyzed = 0
        videoIds.forEachIndexed { index, videoId ->
            // force = true: even DONE rows are redone so the muscle layer fills in.
            val outcome = pipeline.ensureAnalyzed(videoId, force = true)
            if (outcome == BodyPartAnalysisPipeline.Outcome.ANALYZED) analyzed++
            setProgress(
                androidx.work.workDataOf(
                    PROGRESS_DONE to index + 1,
                    PROGRESS_TOTAL to videoIds.size,
                )
            )
        }
        Log.i(TAG, "Reanalyze library finished: $analyzed/${videoIds.size} analyzed")
        return Result.success()
    }

    companion object {
        const val TAG = "ReanalyzeLibraryWorker"
        const val UNIQUE_NAME = "octo_reanalyze_library"
        const val PROGRESS_DONE = "done"
        const val PROGRESS_TOTAL = "total"
    }
}
