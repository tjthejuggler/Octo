package com.example.octo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Pending-session row with video title (JOIN) for the review banners/sheets (Phase 22). */
data class SessionWithTitle(
    val autoId: Long,
    val videoId: String,
    val title: String?, // null when the video was deleted
    val startedAt: Long,
    val durationSeconds: Int,
)

/** Per-video most-recent session timestamp — input for the library "Recently done" sort. */
data class VideoLastPlayedRow(
    val videoId: String,
    val lastPlayedAt: Long,
)

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE autoId = :autoId")
    suspend fun byId(autoId: Long): SessionEntity?

    /** Sessions on one local day (yyyy-MM-dd), oldest first. */
    @Query(
        """
        SELECT * FROM sessions
        WHERE startedAt >= :dayStartMillis AND startedAt < :dayEndMillis
        ORDER BY startedAt ASC
        """
    )
    fun sessionsForDay(dayStartMillis: Long, dayEndMillis: Long): Flow<List<SessionEntity>>

    /**
     * PENDING sessions (Phase 22): played but not yet explicitly confirmed. With video
     * titles for the review surfaces. Zero-time rows are ignored (they're transient
     * player-open artifacts swept by [deleteEmptyUnfinished]).
     */
    @Query(
        """
        SELECT s.autoId AS autoId, s.videoId AS videoId, v.title AS title,
               s.startedAt AS startedAt, s.durationSeconds AS durationSeconds
        FROM sessions s LEFT JOIN videos v ON v.id = s.videoId
        WHERE s.confirmed = 0 AND s.durationSeconds > 0
        ORDER BY s.startedAt DESC
        """
    )
    fun pendingWithTitles(): Flow<List<SessionWithTitle>>

    /** Pending (unconfirmed, non-empty) sessions for ONE video — video-detail banner. */
    @Query(
        """
        SELECT s.autoId AS autoId, s.videoId AS videoId, v.title AS title,
               s.startedAt AS startedAt, s.durationSeconds AS durationSeconds
        FROM sessions s LEFT JOIN videos v ON v.id = s.videoId
        WHERE s.videoId = :videoId AND s.confirmed = 0 AND s.durationSeconds > 0
        ORDER BY s.startedAt DESC
        """
    )
    fun pendingForVideo(videoId: String): Flow<List<SessionWithTitle>>

    /** Most recent session per video — the library "Recently done" sort. */
    @Query("SELECT videoId, MAX(startedAt) AS lastPlayedAt FROM sessions GROUP BY videoId")
    fun lastPlayedPerVideo(): Flow<List<VideoLastPlayedRow>>

    /** Confirmed sessions on one local day — the per-day undo sheet (Phase 22). */
    @Query(
        """
        SELECT s.autoId AS autoId, s.videoId AS videoId, v.title AS title,
               s.startedAt AS startedAt, s.durationSeconds AS durationSeconds
        FROM sessions s LEFT JOIN videos v ON v.id = s.videoId
        WHERE s.confirmed = 1
          AND s.startedAt >= :dayStartMillis AND s.startedAt < :dayEndMillis
        ORDER BY s.startedAt ASC
        """
    )
    fun confirmedForDay(dayStartMillis: Long, dayEndMillis: Long): Flow<List<SessionWithTitle>>

    /** Sessions in an inclusive time range, for stats aggregation (confirmed only — Phase 22). */
    @Query(
        """
        SELECT * FROM sessions
        WHERE confirmed = 1 AND startedAt BETWEEN :fromMillis AND :toMillis
        ORDER BY startedAt ASC
        """
    )
    fun sessionsBetween(fromMillis: Long, toMillis: Long): Flow<List<SessionEntity>>

    @Query("SELECT COALESCE(SUM(durationSeconds), 0) FROM sessions WHERE confirmed = 1")
    fun totalSeconds(): Flow<Int>

    @Query("SELECT COUNT(*) FROM sessions WHERE completed = 1 AND confirmed = 1")
    fun completedCount(): Flow<Int>

    /** True if any session for this video already started within [fromMillis, toMillis]. */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM sessions
            WHERE videoId = :videoId AND startedAt BETWEEN :fromMillis AND :toMillis
        )
        """
    )
    suspend fun hasSessionForVideoInRange(videoId: String, fromMillis: Long, toMillis: Long): Boolean

    @Transaction
    suspend fun recordSession(videoId: String, startedAt: Long, durationSeconds: Int, completed: Boolean): Long =
        insert(SessionEntity(videoId = videoId, startedAt = startedAt, durationSeconds = durationSeconds, completed = completed))

    /** Closes an open session row (Phase 4 player) — leaves it PENDING (confirmed = 0). */
    @Query("UPDATE sessions SET durationSeconds = :durationSeconds, completed = :completed WHERE autoId = :autoId")
    suspend fun finishSession(autoId: Long, durationSeconds: Int, completed: Boolean)

    /**
     * Phase 22 explicit confirmation: marks the session confirmed and stores the
     * credited-per-part snapshot for exact undo.
     */
    @Query(
        "UPDATE sessions SET confirmed = 1, creditedBodyPartsJson = :creditedBodyParts " +
            "WHERE autoId = :autoId AND confirmed = 0"
    )
    suspend fun confirmSession(autoId: Long, creditedBodyParts: String): Int

    /** Removes an abandoned session row (user backed out with no time kept). */
    @Query("DELETE FROM sessions WHERE autoId = :autoId")
    suspend fun delete(autoId: Long)

    /** Phase 8 process-death sweep: drops rows started but never finished with no play time. */
    @Query("DELETE FROM sessions WHERE completed = 0 AND durationSeconds = 0")
    suspend fun deleteEmptyUnfinished()

    /** Sanity aggregate for the stats screen (Phase 7): raw seconds + session count in a range. */
    @Query(
        """
        SELECT COALESCE(SUM(durationSeconds), 0) AS totalSeconds,
               COUNT(*) AS sessions
        FROM sessions
        WHERE confirmed = 1 AND startedAt BETWEEN :fromMillis AND :toMillis
        """
    )
    fun rangeTotals(fromMillis: Long, toMillis: Long): Flow<SessionRangeTotalRow?>
}
