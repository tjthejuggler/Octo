package com.example.octo.data.repository

import com.example.octo.data.local.DownloadState
import com.example.octo.data.local.VideoDao
import com.example.octo.data.local.VideoEntity
import com.example.octo.downloader.VideoDownloadManager
import com.example.octo.llm.BodyPartAnalysisPipeline
import com.example.octo.work.DownloadVideoWorker
import com.example.octo.youtube.YoutubeMetaFetcher
import com.example.octo.youtube.YoutubeUrlParser
import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One exported library row — URL-keyed restore payload (docs/NOTES.md §6 format). */
@Serializable
data class LibraryExportEntry(
    val videoId: String,
    val url: String,
    val title: String,
    val channel: String,
    val durationSeconds: Int = 0,
    val addedAt: Long = 0L,
)

/** Root document written by Settings → "Export library URLs". */
@Serializable
data class LibraryExport(val videos: List<LibraryExportEntry>)

/** Import result counts for the Settings feedback line. */
data class LibraryImportResult(val imported: Int, val skippedExisting: Int)

/**
 * Library façade: DB rows + file artifacts + WorkManager scheduling.
 *
 * Hard requirement (docs/ARCHITECTURE.md §4.2): the canonical-URL row is ALWAYS persisted —
 * a failed download leaves a FAILED/STREAM_ONLY row so the library still shows the video.
 */
class VideoRepository(
    private val context: Context,
    private val videoDao: VideoDao,
    private val videoBodyPartDao: com.example.octo.data.local.VideoBodyPartDao,
    private val metaFetcher: YoutubeMetaFetcher,
    private val downloadManager: VideoDownloadManager,
) {

    /**
     * Phase 5 pipeline, attached post-construction by [com.example.octo.OctoApplication.AppContainer]
     * (the pipeline itself depends on the DB, not on this repository — no cycle).
     */
    @Volatile
    private var analysisPipeline: BodyPartAnalysisPipeline? = null

    fun attachAnalysisPipeline(pipeline: BodyPartAnalysisPipeline) {
        analysisPipeline = pipeline
    }

    /** Library rows joined with their body-part links (Phase 6 uses the links). */
    val library: Flow<List<com.example.octo.data.local.VideoWithBodyParts>> =
        videoDao.observeWithBodyParts().map { rows ->
            rows.sortedByDescending { it.video.addedAt }
        }

    /** Parses [text] (may contain prose around the URL), upserts a PENDING row, enqueues. */
    suspend fun addSharedUrl(text: String): VideoEntity? {
        val parsed = YoutubeUrlParser.parse(text) ?: return null
        val existing = videoDao.getById(parsed.videoId)

        val row = existing?.copy(
            // A re-share of a FAILED video re-queues it; COMPLETED rows stay untouched.
            downloadState = if (existing.downloadState == DownloadState.FAILED) {
                DownloadState.PENDING
            } else {
                existing.downloadState
            },
        ) ?: VideoEntity(
            id = parsed.videoId,
            title = parsed.videoId, // real title arrives with metadata in the worker
            channel = "",
            url = parsed.canonicalUrl,
            addedAt = System.currentTimeMillis(),
        )

        videoDao.upsert(row)
        if (row.downloadState == DownloadState.PENDING) {
            DownloadVideoWorker.enqueue(context, parsed.videoId)
        }
        return row
    }

    /** Re-queues [videoId]; optionally refreshes metadata first (freshness on retry). */
    suspend fun retryDownload(videoId: String): Boolean = withContext(Dispatchers.IO) {
        val row = videoDao.getById(videoId) ?: return@withContext false
        if (row.downloadState == DownloadState.DOWNLOADING) return@withContext false
        videoDao.updateDownloadState(videoId, DownloadState.PENDING)
        DownloadVideoWorker.enqueue(
            context,
            videoId,
        )
        // REPLACE policy would kill a running worker; retry only runs when idle (checked above).
        true
    }

    /** Refreshes metadata for an existing row without touching files (library "restore"). */
    suspend fun refreshMetadata(videoId: String): Boolean = withContext(Dispatchers.IO) {
        val row = videoDao.getById(videoId) ?: return@withContext false
        try {
            val meta = metaFetcher.fetch(videoId)
            videoDao.upsert(
                row.copy(
                    title = meta.title.ifBlank { row.title },
                    channel = meta.channel.ifBlank { row.channel },
                    durationSeconds = meta.durationSeconds,
                ),
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Deletes files + DB row. */
    suspend fun deleteVideo(videoId: String) = withContext(Dispatchers.IO) {
        downloadManager.deleteFiles(videoId)
        videoDao.delete(videoId)
    }

    /** Local storage used by videos + thumbs (settings/stats display). */
    fun diskUsageBytes(): Long {
        val dirs = listOf(File(context.filesDir, "videos"), File(context.filesDir, "thumbs"))
        var total = 0L
        dirs.forEach { dir -> dir.listFiles()?.forEach { f -> total += f.length() } }
        return total
    }

    /**
     * Phase 8 process-death recovery: re-enqueues work for every row stuck in
     * PENDING/DOWNLOADING. DownloadVideoWorker's unique-KEEP policy makes this idempotent —
     * live workers are untouched, only orphaned rows get new requests (which then wait on
     * the CONNECTED constraint when offline).
     */
    suspend fun resumePendingDownloads() {
        val stuck = listOf(DownloadState.PENDING, DownloadState.DOWNLOADING)
        videoDao.getByStates(stuck).forEach { row ->
            videoDao.updateDownloadState(row.id, DownloadState.PENDING)
            DownloadVideoWorker.enqueue(context, row.id)
        }
    }

    /** Exposed for Phase 5 (transcript update hook) and tests. */
    suspend fun get(videoId: String): VideoEntity? = videoDao.getById(videoId)

    /**
     * Phase 24 "Edit parts": replaces the video's body-part links with the user's
     * selection and stamps [com.example.octo.data.local.PartSource.MANUAL] + DONE.
     * Runs in one Room transaction. Confirmed sessions are NOT touched — their
     * creditedBodyPartsJson snapshots stay authoritative for undo (no retroactive
     * credit changes); only FUTURE confirmations use the new links.
     */
    suspend fun applyManualParts(videoId: String, bodyPartIds: Collection<String>): Boolean =
        withContext(Dispatchers.IO) {
            if (videoDao.getById(videoId) == null) return@withContext false
            videoDao.applyManualParts(
                videoId = videoId,
                bodyPartIds = bodyPartIds.toList(),
                videoBodyPartDao = videoBodyPartDao,
                analyzedAt = System.currentTimeMillis(),
            )
            true
        }

    /**
     * Phase 5 trigger entry point: transcript fetch (if needed) + body-part extraction +
     * cross-ref upsert. Fire-and-forget safe — never throws; results land on the row
     * (analysisState + cross-refs) and are observed by the Library UI.
     */
    suspend fun analyzeVideo(videoId: String, force: Boolean = false): BodyPartAnalysisPipeline.Outcome? =
        analysisPipeline?.ensureAnalyzed(videoId, force)

    // ── Phase 8: URL-based library export / import (SAF front-end in Settings) ──

    private val exportJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /** Serializes every row (id + canonical URL + known metadata) to a JSON document. */
    suspend fun exportLibraryJson(): String {
        val entries = videoDao.getAllOnce().map { row ->
            LibraryExportEntry(
                videoId = row.id,
                url = row.url.ifBlank { YoutubeUrlParser.canonicalWatchUrl(row.id) },
                title = row.title,
                channel = row.channel,
                durationSeconds = row.durationSeconds,
                addedAt = row.addedAt,
            )
        }
        return exportJson.encodeToString(LibraryExport(videos = entries))
    }

    /**
     * Restores rows from an exported document. Rows are inserted as STREAM_ONLY —
     * the URL is the restore key and metadata/files are re-fetched on demand via the
     * Library card menu's Download action; nothing is re-downloaded automatically.
     * Rows whose id already exists are skipped (never clobber a live library).
     * @throws kotlinx.serialization.SerializationException on malformed input
     */
    suspend fun importLibraryJson(json: String): LibraryImportResult {
        val document = exportJson.decodeFromString<LibraryExport>(json)
        var imported = 0
        var skipped = 0
        for (entry in document.videos) {
            val id = entry.videoId.trim()
            if (id.isEmpty()) continue
            if (videoDao.getById(id) != null) {
                skipped++
                continue
            }
            videoDao.upsert(
                VideoEntity(
                    id = id,
                    title = entry.title.ifBlank { id },
                    channel = entry.channel,
                    url = entry.url.ifBlank { YoutubeUrlParser.canonicalWatchUrl(id) },
                    durationSeconds = entry.durationSeconds,
                    addedAt = if (entry.addedAt > 0) entry.addedAt else System.currentTimeMillis(),
                    downloadState = DownloadState.STREAM_ONLY,
                ),
            )
            imported++
        }
        return LibraryImportResult(imported = imported, skippedExisting = skipped)
    }
}
