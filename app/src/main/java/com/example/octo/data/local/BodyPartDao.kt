package com.example.octo.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BodyPartDao {

    @Upsert
    suspend fun upsert(row: BodyPartStretchEntity)

    @Query("SELECT * FROM body_part_stretch WHERE date = :date AND bodyPartId = :bodyPartId")
    suspend fun row(date: String, bodyPartId: String): BodyPartStretchEntity?

    /**
     * Atomic "+= minutes, += sessions". Uses a suspending @Transaction (serialized by Room)
     * instead of SQLite UPSERT syntax, which needs SQLite 3.24+ — API 26/27 devices only
     * ship 3.18/3.19 and would crash at runtime.
     */
    @Transaction
    suspend fun increment(date: String, bodyPartId: String, minutes: Int, sessions: Int) {
        val existing = row(date, bodyPartId)
        if (existing == null) {
            upsert(BodyPartStretchEntity(date = date, bodyPartId = bodyPartId, minutes = minutes, sessions = sessions))
        } else {
            upsert(existing.copy(minutes = existing.minutes + minutes, sessions = existing.sessions + sessions))
        }
    }

    @Query("SELECT * FROM body_part_stretch WHERE date = :date")
    fun forDay(date: String): Flow<List<BodyPartStretchEntity>>

    /** Inclusive date range, for stats screens and Tail backfill (Phase 7). */
    @Query(
        """
        SELECT * FROM body_part_stretch
        WHERE date BETWEEN :fromDate AND :toDate
        ORDER BY date ASC
        """
    )
    fun forRange(fromDate: String, toDate: String): Flow<List<BodyPartStretchEntity>>

    @Query("SELECT * FROM body_part_stretch WHERE date = :date")
    suspend fun forDayOnce(date: String): List<BodyPartStretchEntity>

    /**
     * Phase 22 exact-undo counterpart of [increment]: subtracts the session's credited
     * snapshot from the stored aggregate, clamped at zero (never negative), deleting
     * the row entirely when it lands at zero minutes and zero sessions.
     */
    @Transaction
    suspend fun decrement(date: String, bodyPartId: String, minutes: Int, sessions: Int) {
        val existing = row(date, bodyPartId) ?: return
        val (m, s) = CreditedParts.undoTotals(existing.minutes, existing.sessions, minutes)
        if (m == 0 && s == 0) {
            deleteRow(date, bodyPartId)
        } else {
            upsert(existing.copy(minutes = m, sessions = s))
        }
    }

    @Query("DELETE FROM body_part_stretch WHERE date = :date AND bodyPartId = :bodyPartId")
    suspend fun deleteRow(date: String, bodyPartId: String)

    @Query("DELETE FROM body_part_stretch WHERE date = :date")
    suspend fun deleteForDay(date: String)

    // ── Phase 7 stats aggregates (SQL GROUP BY; see docs/ARCHITECTURE.md §6 stats) ──

    /** SUM(minutes), SUM(sessions), COUNT(DISTINCT date) over an inclusive date window. */
    @Query(
        """
        SELECT COALESCE(SUM(minutes), 0) AS minutes,
               COALESCE(SUM(sessions), 0) AS sessions,
               COUNT(DISTINCT date) AS activeDays
        FROM body_part_stretch
        WHERE date BETWEEN :fromDate AND :toDate
        """
    )
    fun rangeTotals(fromDate: String, toDate: String): Flow<RangeTotalRow?>

    /** Per-body-part totals in a window — the stats frequency bars. */
    @Query(
        """
        SELECT bodyPartId AS bodyPartId,
               COALESCE(SUM(minutes), 0) AS minutes,
               COALESCE(SUM(sessions), 0) AS sessions
        FROM body_part_stretch
        WHERE date BETWEEN :fromDate AND :toDate
        GROUP BY bodyPartId
        ORDER BY minutes DESC
        """
    )
    fun partTotalsInRange(fromDate: String, toDate: String): Flow<List<PartTotalRow>>

    /** Per-day totals in a window — the trend chart (bucketed client-side). */
    @Query(
        """
        SELECT date AS date,
               COALESCE(SUM(minutes), 0) AS minutes,
               COALESCE(SUM(sessions), 0) AS sessions
        FROM body_part_stretch
        WHERE date BETWEEN :fromDate AND :toDate
        GROUP BY date
        ORDER BY date ASC
        """
    )
    fun dailyTotalsInRange(fromDate: String, toDate: String): Flow<List<DailyTotalRow>>

    /** Distinct active dates in a window — streak walking (last ~365 days). */
    @Query("SELECT DISTINCT date FROM body_part_stretch WHERE date BETWEEN :fromDate AND :toDate")
    fun activeDates(fromDate: String, toDate: String): Flow<List<String>>

    // ── Phase 11: per-body-part detail screen ("part/{bodyPartId}") ──

    /** All-time SUM/COUNT for ONE part (header totals). */
    @Query(
        """
        SELECT COALESCE(SUM(minutes), 0) AS minutes,
               COALESCE(SUM(sessions), 0) AS sessions,
               COUNT(DISTINCT date) AS activeDays
        FROM body_part_stretch
        WHERE bodyPartId = :bodyPartId
        """
    )
    fun allTimeTotalsFor(bodyPartId: String): Flow<RangeTotalRow?>

    /** Per-day totals for ONE part in a window (trend chart input), ascending. */
    @Query(
        """
        SELECT date AS date,
               COALESCE(SUM(minutes), 0) AS minutes,
               COALESCE(SUM(sessions), 0) AS sessions
        FROM body_part_stretch
        WHERE bodyPartId = :bodyPartId AND date BETWEEN :fromDate AND :toDate
        GROUP BY date
        ORDER BY date ASC
        """
    )
    fun forPartForRange(bodyPartId: String, fromDate: String, toDate: String): Flow<List<DailyTotalRow>>

    /** History list for ONE part, most recent first, SQL-side capped. */
    @Query(
        """
        SELECT * FROM body_part_stretch
        WHERE bodyPartId = :bodyPartId
        ORDER BY date DESC
        LIMIT :limit
        """
    )
    fun dailyRowsForPart(bodyPartId: String, limit: Int): Flow<List<BodyPartStretchEntity>>
}
