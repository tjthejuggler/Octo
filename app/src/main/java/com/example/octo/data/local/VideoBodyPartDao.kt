package com.example.octo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Cross-ref writes for the Phase 5 analysis pipeline. Lives in its own DAO because
 * [VideoDao] is row-oriented; replace semantics (delete-then-insert) live in one
 * suspend [Transaction] so the library never observes a half-written link set.
 */
@Dao
interface VideoBodyPartDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(links: List<VideoBodyPartCrossRef>)

    @Query("DELETE FROM video_body_part_cross_ref WHERE videoId = :videoId")
    suspend fun deleteForVideo(videoId: String)

    /** Idempotent replace of [videoId]'s body-part links. */
    @Transaction
    suspend fun replaceForVideo(videoId: String, bodyPartIds: Collection<String>) {
        deleteForVideo(videoId)
        if (bodyPartIds.isNotEmpty()) {
            insertAll(bodyPartIds.map { VideoBodyPartCrossRef(videoId, it) })
        }
    }

    /** Phase 11: every library video tagged with [bodyPartId] (+ its other links), newest first. */
    @Transaction
    @Query(
        """
        SELECT * FROM videos
        WHERE id IN (SELECT videoId FROM video_body_part_cross_ref WHERE bodyPartId = :bodyPartId)
        ORDER BY addedAt DESC
        """
    )
    fun getVideosForPart(bodyPartId: String): Flow<List<VideoWithBodyParts>>
}
