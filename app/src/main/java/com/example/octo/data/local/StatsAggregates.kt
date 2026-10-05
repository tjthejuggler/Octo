package com.example.octo.data.local

/**
 * Projection rows for the Phase 7 stats screen — SQL GROUP BY / aggregate results
 * over [BodyPartStretchEntity] (written on session finish) and [SessionEntity]
 * (sanity cross-check). Room maps columns by name, so the aliases in the DAO
 * queries must match these property names exactly.
 */

/** Totals over a date window: [BodyPartDao.rangeTotals]. */
data class RangeTotalRow(
    val minutes: Int,
    val sessions: Int,
    val activeDays: Int,
)

/** Per-body-part totals in a window: [BodyPartDao.partTotalsInRange]. */
data class PartTotalRow(
    val bodyPartId: String,
    val minutes: Int,
    val sessions: Int,
)

/** Per-day totals in a window: [BodyPartDao.dailyTotalsInRange]. */
data class DailyTotalRow(
    val date: String,
    val minutes: Int,
    val sessions: Int,
)

/** Raw sessions sanity aggregate in a window: [SessionDao.rangeTotals]. */
data class SessionRangeTotalRow(
    val totalSeconds: Int,
    val sessions: Int,
)
