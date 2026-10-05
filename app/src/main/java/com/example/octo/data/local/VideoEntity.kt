package com.example.octo.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A library entry. PK is the YouTube videoId — the URL is the restore key (§4.2). */
@Entity(tableName = "videos")
data class VideoEntity(
    @PrimaryKey val id: String, // YouTube videoId
    val title: String,
    val channel: String,
    val url: String, // canonical https://www.youtube.com/watch?v=<id>
    val localFilePath: String? = null, // app-private file, null while not downloaded
    val thumbnailPath: String? = null, // cached hqdefault.jpg, null until fetched
    val durationSeconds: Int = 0,
    val addedAt: Long, // epoch millis
    val downloadedAt: Long? = null, // epoch millis of last successful download
    val downloadState: DownloadState = DownloadState.PENDING,
    val transcriptText: String? = null, // full plain text (Phase 5)
    val transcriptSource: TranscriptSource? = null, // which fallback chain tier produced it
    val analysisState: AnalysisState = AnalysisState.NONE, // Phase 5 pipeline lifecycle
    val analyzedAt: Long? = null, // epoch millis of last completed/failed analysis
    val partSource: PartSource? = null, // Phase 24: how the links were made (LLM/heuristic/manual)
)
