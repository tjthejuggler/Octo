package com.example.octo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Cross-ref writes for the muscle-detail analysis layer. Same replace
 * semantics as [VideoBodyPartDao] — delete-then-insert inside one suspend
 * Transaction so observers never see a half-written muscle set.
 */
@Dao
interface VideoMuscleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(links: List<VideoMuscleCrossRef>)

    @Query("DELETE FROM video_muscle_cross_ref WHERE videoId = :videoId")
    suspend fun deleteForVideo(videoId: String)

    /** Idempotent replace of [videoId]'s muscle links. */
    @Transaction
    suspend fun replaceForVideo(videoId: String, muscleIds: Collection<String>) {
        deleteForVideo(videoId)
        if (muscleIds.isNotEmpty()) {
            insertAll(muscleIds.map { VideoMuscleCrossRef(videoId, it) })
        }
    }

    @Query("SELECT muscleId FROM video_muscle_cross_ref WHERE videoId = :videoId")
    suspend fun muscleIdsForVideo(videoId: String): List<String>

    @Query("SELECT muscleId FROM video_muscle_cross_ref WHERE videoId = :videoId")
    fun observeMuscleIdsForVideo(videoId: String): Flow<List<String>>

    /**
     * Per-muscle activity in a window: distinct DAYS on which any CONFIRMED
     * session covered each muscle (mirrors the dashboard's confirmed-sessions
     * rule; date bucketed in local time). Reuses [MuscleVideoCountRow] — here
     * videoCount carries the day count.
     */
    @Query(
        """
        SELECT vm.muscleId, COUNT(DISTINCT date(s.startedAt / 1000, 'unixepoch', 'localtime')) AS videoCount
        FROM video_muscle_cross_ref vm
        JOIN sessions s ON s.videoId = vm.videoId
        WHERE s.confirmed = 1 AND s.startedAt BETWEEN :fromMillis AND :toMillis
        GROUP BY vm.muscleId
        """
    )
    fun muscleDaysInRange(fromMillis: Long, toMillis: Long): Flow<List<MuscleVideoCountRow>>

    /** Library coverage stats: how many videos are tagged per muscle. */
    @Query(
        """
        SELECT muscleId, COUNT(*) AS videoCount
        FROM video_muscle_cross_ref
        GROUP BY muscleId
        """
    )
    fun videoCountsByMuscle(): Flow<List<MuscleVideoCountRow>>

    /**
     * Muscles stretched on a day: distinct muscle ids linked to any video with a
     * CONFIRMED session whose start falls inside [dayStartMillis, dayEndMillis].
     * Mirrors the "confirmed sessions only" rule of the dashboard aggregates.
     */
    @Query(
        """
        SELECT DISTINCT vm.muscleId
        FROM video_muscle_cross_ref vm
        WHERE vm.videoId IN (
            SELECT videoId FROM sessions
            WHERE confirmed = 1 AND startedAt BETWEEN :dayStartMillis AND :dayEndMillis
        )
        """
    )
    fun observeMusclesForDay(dayStartMillis: Long, dayEndMillis: Long): Flow<List<String>>
}
