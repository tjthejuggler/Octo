package com.example.octo.ui.body

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Session-count color scale for the body map (dashboard + video-detail preview).
 *
 * A part's color encodes HOW MANY sets were logged for it on the selected day:
 *   0 → dull deep red · 1 → orange · 2 → green · 3 → blue · 4 → pink ·
 *   5 → yellow · 6+ → white.
 *
 * The video-detail screen previews the SAME scale one step ahead: a part
 * covered by the shown video renders [todayTierColor](count + 1) — what the
 * dashboard WILL look like after logging that video now — while every other
 * part (including ones already done today) stays the idle red.
 */
val BodyTierIdleRed = Color(0xFF9C4141)
val BodyTierOrange = Color(0xFFFF8C3B)
val BodyTierGreen = Color(0xFF4FC15C)
val BodyTierBlue = Color(0xFF4D8DF7)
val BodyTierPink = Color(0xFFFF6EC7)
val BodyTierYellow = Color(0xFFF7C948)
val BodyTierWhite = Color(0xFFF2F2F2)

/** Color for a part with [count] sets logged today (6+ clamps to white). */
fun todayTierColor(count: Int): Color = when {
    count <= 0 -> BodyTierIdleRed
    count == 1 -> BodyTierOrange
    count == 2 -> BodyTierGreen
    count == 3 -> BodyTierBlue
    count == 4 -> BodyTierPink
    count == 5 -> BodyTierYellow
    else -> BodyTierWhite
}

/**
 * Continuous usage-intensity scale for the stats-screen bars — percentage-based,
 * unlike the daily set-count scale above.
 *
 * [fraction] is a body part's usage RELATIVE to the busiest part in the SELECTED
 * window (minutes / max-minutes, 0..1). Because it is recomputed per window,
 * switching 7D ↔ 30D ↔ 90D recolors the bars whenever the usage percentages
 * shift — a part dominating this week reads high-color in the week view and
 * drops toward orange/red in the 90-day view if it was rarely done there.
 *
 * 0 → idle red; everything above rides the SAME tier sequence as the daily
 * scale (orange → green → blue → pink → yellow → white), smoothly interpolated
 * so "more usage = higher color" with no hard steps between tiers.
 */
fun usageTierColor(fraction: Float): Color {
    if (fraction <= 0f) return BodyTierIdleRed
    val scaled = fraction.coerceIn(0f, 1f) * UsagePalette.lastIndex
    val i = scaled.toInt().coerceAtMost(UsagePalette.lastIndex - 1)
    return lerp(UsagePalette[i], UsagePalette[i + 1], scaled - i)
}

/** Tier ramp for [usageTierColor] — the daily tier colors reused in order. */
private val UsagePalette = listOf(
    BodyTierOrange,
    BodyTierGreen,
    BodyTierBlue,
    BodyTierPink,
    BodyTierYellow,
    BodyTierWhite,
)
