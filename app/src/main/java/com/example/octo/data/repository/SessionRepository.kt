package com.example.octo.data.repository

import androidx.room.withTransaction
import com.example.octo.data.local.CreditedParts
import com.example.octo.data.local.OctoDatabase
import com.example.octo.data.local.SessionEntity
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Session lifecycle + daily body-part aggregation (Phase 4, docs/ARCHITECTURE.md §7).
 *
 * Phase 22 — EXPLICIT STRETCH CONFIRMATION: playing a video records NOTHING except a
 * PENDING session row. Only [confirmSession] (user tapped "Yes, log it" / "Log it")
 * increments the daily [com.example.octo.data.local.BodyPartStretchEntity] aggregates
 * and returns the credited minutes for the caller's single Tail broadcast.
 * [undoConfirmedSession] reverses a confirmation exactly via the credited-per-part
 * snapshot stored on the row at confirm time.
 */
class SessionRepository(private val db: OctoDatabase) {

    /** Library row for the player screen (title/duration/files). */
    suspend fun getVideo(videoId: String): com.example.octo.data.local.VideoEntity? =
        db.videoDao().getById(videoId)

    /** Opens a session row; returns its autoId for the later [complete]/[discard]. */
    suspend fun start(videoId: String): Long =
        db.sessionDao().insert(
            SessionEntity(
                videoId = videoId,
                startedAt = System.currentTimeMillis(),
                durationSeconds = 0,
                completed = false,
            ),
        )

    /**
     * Closes session [sessionRowId] in the PENDING (unconfirmed) state: persists the raw
     * [elapsedSeconds] + completed flag. NO aggregate increments, NO Tail side effects —
     * those happen only in [confirmSession] after the user explicitly logs the stretches.
     */
    suspend fun complete(
        sessionRowId: Long,
        elapsedSeconds: Int,
        completed: Boolean,
    ) {
        db.sessionDao().finishSession(sessionRowId, elapsedSeconds, completed)
    }

    /**
     * Phase 22 explicit confirmation — the ONLY path that records stretches:
     *  1. credits the session's minutes to the daily aggregates for the session's
     *     START DATE (honest: the day the stretches were actually done; a video played
     *     before midnight and confirmed after colors yesterday),
     *  2. snapshots the per-part credited minutes on the row (exact-undo input),
     *  3. marks confirmed.
     *
     * Videos whose body-part links were deleted while pending confirm with whatever
     * parts are STILL linked (possibly none → session-only log, no part credits).
     *
     * @return credited minutes (0 when ≤ 30 s) for the caller's Tail broadcast, or null
     *   when the row was already confirmed/deleted (guard against double-taps).
     */
    suspend fun confirmSession(sessionRowId: Long): Int? {
        var creditedMinutes: Int? = null
        db.withTransaction {
            val session = db.sessionDao().byId(sessionRowId) ?: return@withTransaction
            if (session.confirmed) return@withTransaction

            // FB5 fix: an EXPLICIT user confirmation ("Yes, log it" / "Log it")
            // always credits at least 1 minute. The >30 s guard exists for the
            // old AUTO-log path; the only auto path left is onCleared's safety
            // net, which saves PENDING rows — never confirmed ones. A confirmed
            // zero-minute row used to be a black hole: marked confirmed (so the
            // banner disappeared) but wrote no aggregates → nothing in stats,
            // history, or the colored figure.
            val minutes = creditedMinutes(session.durationSeconds).coerceAtLeast(1)
            val date = sessionDate(session.startedAt)
            val links = db.videoDao().getWithBodyParts(session.videoId)?.bodyParts.orEmpty()
            val credited = links.associate { it.bodyPartId to minutes }.filterValues { it > 0 }
            for ((partId, m) in credited) {
                db.bodyPartDao().increment(date = date, bodyPartId = partId, minutes = m, sessions = 1)
            }
            db.sessionDao().confirmSession(sessionRowId, CreditedParts.encode(credited))
            creditedMinutes = minutes
        }
        return creditedMinutes
    }

    /** Deletes a PENDING session row — "Discard" on the review banners. No-op if confirmed. */
    suspend fun discardSession(sessionRowId: Long) {
        db.withTransaction {
            val session = db.sessionDao().byId(sessionRowId) ?: return@withTransaction
            if (!session.confirmed) db.sessionDao().delete(sessionRowId)
        }
    }

    /**
     * Phase 22 undo of a CONFIRMED session ("easy way to un-play a video for a day"):
     * deletes the session row and subtracts its credited snapshot from the aggregates
     * on the session's start date, clamped at zero (see [CreditedParts.undoTotals]).
     * The snapshot is read regardless of the video's current link state, so undo stays
     * exact even after the video's body parts were re-analyzed or the video deleted.
     */
    suspend fun undoConfirmedSession(sessionRowId: Long) {
        db.withTransaction {
            val session = db.sessionDao().byId(sessionRowId) ?: return@withTransaction
            if (!session.confirmed) return@withTransaction
            val date = sessionDate(session.startedAt)
            for ((partId, minutes) in CreditedParts.decode(session.creditedBodyParts)) {
                db.bodyPartDao().decrement(date = date, bodyPartId = partId, minutes = minutes, sessions = 1)
            }
            db.sessionDao().delete(sessionRowId)
        }
    }

    /** Abandons a session without recording anything (user backed out with no time kept). */
    suspend fun discard(sessionRowId: Long) {
        db.sessionDao().delete(sessionRowId)
    }

    /**
     * Phase 8 process-death recovery: removes rows the player opened (via [start]) but
     * never finished — zero seconds, not completed. Real sessions always have either
     * elapsed time or the completed flag, so this can't touch recorded history.
     */
    suspend fun deleteEmptySessions() {
        db.sessionDao().deleteEmptyUnfinished()
    }

    /** > 30 s → round to nearest minute, minimum 1; otherwise 0. */
    fun creditedMinutes(elapsedSeconds: Int): Int =
        if (elapsedSeconds > MINIMUM_THRESHOLD_SECONDS) {
            (elapsedSeconds / 60.0).roundToInt().coerceAtLeast(1)
        } else {
            0
        }

    private fun sessionDate(startedAtMillis: Long): String =
        Instant.ofEpochMilli(startedAtMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(DateTimeFormatter.ISO_LOCAL_DATE)

    private companion object {
        const val MINIMUM_THRESHOLD_SECONDS = 30
    }
}
