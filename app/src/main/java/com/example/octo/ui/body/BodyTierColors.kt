package com.example.octo.ui.body

import androidx.compose.ui.graphics.Color

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
