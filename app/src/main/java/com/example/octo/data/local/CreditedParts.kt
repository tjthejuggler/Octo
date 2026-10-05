package com.example.octo.data.local

/**
 * Phase 22 — codec + clamp math for the per-session credited-parts snapshot
 * (`SessionEntity.creditedBodyParts`). Pure Kotlin (no Android deps) so the
 * undo-clamp arithmetic is unit-testable on the JVM.
 *
 * Format: `partId:minutes,partId:minutes` (empty string / null = no part credits —
 * e.g. a session-only log for a video whose body-part links were deleted while pending).
 */
object CreditedParts {

    fun encode(parts: Map<String, Int>): String =
        parts.entries
            .filter { it.value > 0 }
            .joinToString(",") { "${it.key}:${it.value}" }

    fun decode(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = mutableMapOf<String, Int>()
        for (entry in raw.split(',')) {
            val idx = entry.lastIndexOf(':')
            if (idx <= 0 || idx == entry.length - 1) continue
            val minutes = entry.substring(idx + 1).toIntOrNull() ?: continue
            if (minutes > 0) out[entry.substring(0, idx)] = minutes
        }
        return out
    }

    /**
     * Undo-clamp: subtract [credited] minutes / one session from the stored aggregate,
     * never below zero (a prior manual aggregate reset could have already lowered it).
     * A row that lands at 0 minutes AND 0 sessions is deleted by the caller.
     */
    fun undoTotals(
        storedMinutes: Int,
        storedSessions: Int,
        creditedMinutes: Int,
    ): Pair<Int, Int> = (
        (storedMinutes - creditedMinutes).coerceAtLeast(0) to
            (storedSessions - 1).coerceAtLeast(0)
        )
}
