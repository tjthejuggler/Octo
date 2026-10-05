package com.example.octo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface VideoDao {

    /** Insert or replace a full row (e.g. metadata refresh). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(video: VideoEntity)

    @Update
    suspend fun update(video: VideoEntity)

    @Query("SELECT * FROM videos ORDER BY addedAt DESC")
    fun getAll(): Flow<List<VideoEntity>>

    /** One-shot read for library export (Phase 8). */
    @Query("SELECT * FROM videos ORDER BY addedAt DESC")
    suspend fun getAllOnce(): List<VideoEntity>

    @Transaction
    @Query("SELECT * FROM videos ORDER BY addedAt DESC")
    fun observeWithBodyParts(): Flow<List<VideoWithBodyParts>>

    @Query("SELECT * FROM videos WHERE id = :videoId")
    suspend fun getById(videoId: String): VideoEntity?

    /** Row + its body-part links, one-shot (Phase 4 session aggregation). */
    @Transaction
    @Query("SELECT * FROM videos WHERE id = :videoId")
    suspend fun getWithBodyParts(videoId: String): VideoWithBodyParts?

    @Query("SELECT * FROM videos WHERE id = :videoId")
    fun getByIdFlow(videoId: String): Flow<VideoEntity?>

    /** Phase 12: live row + body-part links for the video detail screen. */
    @Transaction
    @Query("SELECT * FROM videos WHERE id = :videoId")
    fun observeWithBodyPartsById(videoId: String): Flow<VideoWithBodyParts?>

    /** Rows in any of [states] — process-death recovery sweep (Phase 8). */
    @Query("SELECT * FROM videos WHERE downloadState IN (:states)")
    suspend fun getByStates(states: List<DownloadState>): List<VideoEntity>

    @Query("UPDATE videos SET localFilePath = :path, downloadedAt = :downloadedAt, downloadState = :state WHERE id = :videoId")
    suspend fun updateFileState(videoId: String, path: String?, downloadedAt: Long?, state: DownloadState)

    @Query("UPDATE videos SET downloadState = :state WHERE id = :videoId")
    suspend fun updateDownloadState(videoId: String, state: DownloadState)

    @Query("UPDATE videos SET transcriptText = :text, transcriptSource = :source WHERE id = :videoId")
    suspend fun updateTranscript(videoId: String, text: String?, source: TranscriptSource?)

    // ── Phase 5: analysis pipeline ──

    @Query("UPDATE videos SET analysisState = :state, analyzedAt = :analyzedAt WHERE id = :videoId")
    suspend fun updateAnalysisState(videoId: String, state: AnalysisState, analyzedAt: Long?)

    /** Marks RUNNING before a (re)analysis attempt; cleared to DONE/FAILED by the pipeline. */
    @Query("UPDATE videos SET analysisState = 'RUNNING', analyzedAt = NULL WHERE id = :videoId")
    suspend fun markAnalysisRunning(videoId: String)

    /** Phase 24: records HOW the current links were produced (set together with DONE). */
    @Query("UPDATE videos SET partSource = :source WHERE id = :videoId")
    suspend fun updatePartSource(videoId: String, source: PartSource)

    /**
     * Phase 24 "Edit parts" commit: replace the link set, stamp MANUAL provenance and
     * DONE in one transaction — the UI never observes a half-applied manual edit.
     * NOTE: this never touches confirmed sessions; their creditedBodyPartsJson
     * snapshots remain authoritative for undo (no retroactive credit changes).
     */
    @Transaction
    suspend fun applyManualParts(
        videoId: String,
        bodyPartIds: List<String>,
        videoBodyPartDao: VideoBodyPartDao,
        analyzedAt: Long,
    ) {
        videoBodyPartDao.replaceForVideo(videoId, bodyPartIds)
        updateAnalysisState(videoId, AnalysisState.DONE, analyzedAt)
        updatePartSource(videoId, PartSource.MANUAL)
    }

    @Query("DELETE FROM videos WHERE id = :videoId")
    suspend fun delete(videoId: String)
}
