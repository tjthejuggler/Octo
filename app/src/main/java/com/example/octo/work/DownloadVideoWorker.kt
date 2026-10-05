package com.example.octo.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.octo.OctoApplication
import com.example.octo.data.local.DownloadState
import com.example.octo.data.local.VideoEntity
import java.util.concurrent.TimeUnit

/**
 * Fetches metadata → downloads video (+audio) + thumbnail → COMPLETED.
 * Transcript is Phase 5 — hook left below.
 *
 * Any failure after [MAX_ATTEMPTS] runs leaves the row FAILED **but keeps the URL row** so the
 * library still shows the video (docs/ARCHITECTURE.md §4.2 hard requirement).
 */
class DownloadVideoWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val videoId = inputData.requireVideoId()
        val app = applicationContext.applicationContext as OctoApplication
        val container = app.appContainer
        val dao = container.database.videoDao()
        val existing = dao.getById(videoId) ?: return Result.failure() // deleted mid-flight

        dao.updateDownloadState(videoId, DownloadState.DOWNLOADING)
        try {
            val result = container.videoDownloadManager.download(videoId) { read, total ->
                setProgressAsync(
                    workDataOf(
                        PROGRESS_BYTES to read,
                        PROGRESS_TOTAL to total,
                    ),
                )
            }

            // Re-read: user could have deleted the row while bytes were flowing.
            val current = dao.getById(videoId) ?: return Result.failure()

            val localPath: String
            val thumbPath: String?
            when (val outcome = result.outcome) {
                is com.example.octo.downloader.VideoDownloadManager.Outcome.Muxed -> {
                    localPath = outcome.file.absolutePath
                    thumbPath = outcome.thumbnailFile?.absolutePath ?: current.thumbnailPath
                }

                is com.example.octo.downloader.VideoDownloadManager.Outcome.Dual -> {
                    localPath = outcome.videoFile.absolutePath // audio derivable (§4.2)
                    thumbPath = outcome.thumbnailFile?.absolutePath ?: current.thumbnailPath
                }
            }

            dao.update(
                current.copy(
                    title = result.meta.title.ifBlank { current.title },
                    channel = result.meta.channel.ifBlank { current.channel },
                    durationSeconds = result.meta.durationSeconds,
                    localFilePath = localPath,
                    thumbnailPath = thumbPath,
                    downloadState = DownloadState.COMPLETED,
                    downloadedAt = System.currentTimeMillis(),
                ),
            )

            // Phase 5 hook: transcript + body-part extraction run right after a successful
            // download. Fire-and-forget — never fails the download result; ensureAnalyzed
            // collapses all errors into AnalysisState.FAILED on the row.
            try {
                container.videoRepository.analyzeVideo(videoId)
            } catch (t: Throwable) {
                android.util.Log.w("DownloadVideoWorker", "Post-download analysis trigger failed", t)
            }
            return Result.success()
        } catch (t: Throwable) {
            // Row must survive regardless of outcome — reset via getById so we never clobber
            // metadata updated by a concurrent retry.
            val row = dao.getById(videoId)
            if (row != null) {
                dao.update(
                    row.copy(
                        downloadState = DownloadState.FAILED,
                        // keep localFilePath only if a previous download succeeded
                        localFilePath = row.downloadedAt?.let { row.localFilePath },
                    ),
                )
            }
            return if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                // STREAM_ONLY is the acceptable degraded state (§4.2): URL row persists,
                // nothing local to play.
                Result.failure()
            }
        }
    }

    private fun Data.requireVideoId(): String =
        getString(KEY_VIDEO_ID) ?: error("DownloadVideoWorker requires $KEY_VIDEO_ID")

    companion object {
        const val KEY_VIDEO_ID = "videoId"
        const val PROGRESS_BYTES = "progressBytes"
        const val PROGRESS_TOTAL = "progressTotal"
        const val MAX_ATTEMPTS = 3

        /** Unique-work name prefix; full name is "$UNIQUE_WORK_NAME_PREFIX:$videoId". */
        const val UNIQUE_WORK_NAME_PREFIX = "download_video"

        fun uniqueWorkName(videoId: String) = "$UNIQUE_WORK_NAME_PREFIX:$videoId"

        /** Enqueue helper — unique per videoId, KEEP so re-shares don't restart running work. */
        fun enqueue(context: Context, videoId: String) {
            val request = OneTimeWorkRequestBuilder<DownloadVideoWorker>()
                .setInputData(workDataOf(KEY_VIDEO_ID to videoId))
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .addTag(UNIQUE_WORK_NAME_PREFIX)
                .addTag(videoId) // lets UI match WorkInfos to rows without inputData
                .build()

            androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(videoId),
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
