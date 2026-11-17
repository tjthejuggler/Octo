package com.example.octo.ui.library

import androidx.compose.ui.graphics.Color
import com.example.octo.ui.body.BodyTierBlue
import com.example.octo.ui.body.BodyTierGreen
import com.example.octo.ui.body.BodyTierIdleRed
import com.example.octo.ui.body.BodyTierOrange
import com.example.octo.ui.body.BodyTierPink
import com.example.octo.ui.body.BodyTierWhite
import com.example.octo.ui.body.BodyTierYellow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * FB28 library-card recency coloring: a card's glass pane is tinted by HOW LONG
 * AGO the video was last played, riding the SAME tier palette as the body map
 * so both screens speak one color language:
 *
 *   today → white · yesterday → yellow · 2 days ago → pink · rest of this
 *   week → blue · the week before → green · the week before that → orange ·
 *   older than that (or never played) → red.
 *
 * Tints are blended into the sea-glass pane at a muted fraction (see
 * [com.example.octo.ui.components.GlassCard]'s tint parameter) — a slight but
 * noticeable hue shift, not a solid fill.
 */
enum class RecencyBucket {
    TODAY,
    YESTERDAY,
    DAY_BEFORE,
    THIS_WEEK,
    LAST_WEEK,
    TWO_WEEKS_AGO,
    OLDER,
    NEVER,
}

/**
 * Whole local days between [lastPlayedAt]'s date and [today]; null when the
 * video was never played (null/zero timestamp). Negative (clock skew) clamps
 * to 0 = today upstream.
 */
fun daysSince(lastPlayedAt: Long?, today: LocalDate, zone: ZoneId): Long? {
    if (lastPlayedAt == null || lastPlayedAt <= 0L) return null
    val date = Instant.ofEpochMilli(lastPlayedAt).atZone(zone).toLocalDate()
    return (today.toEpochDay() - date.toEpochDay()).coerceAtLeast(0L)
}

/** Bucket for a card tint; never-played reads as [RecencyBucket.NEVER] (reddest). */
fun recencyBucket(
    lastPlayedAt: Long?,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): RecencyBucket {
    val days = daysSince(lastPlayedAt, today, zone) ?: return RecencyBucket.NEVER
    return when {
        days <= 0L -> RecencyBucket.TODAY
        days == 1L -> RecencyBucket.YESTERDAY
        days == 2L -> RecencyBucket.DAY_BEFORE
        days <= 6L -> RecencyBucket.THIS_WEEK
        days <= 13L -> RecencyBucket.LAST_WEEK
        days <= 20L -> RecencyBucket.TWO_WEEKS_AGO
        else -> RecencyBucket.OLDER
    }
}

/** Tier-palette color for the bucket (same anchors as the body map). */
fun RecencyBucket.tierColor(): Color = when (this) {
    RecencyBucket.TODAY -> BodyTierWhite
    RecencyBucket.YESTERDAY -> BodyTierYellow
    RecencyBucket.DAY_BEFORE -> BodyTierPink
    RecencyBucket.THIS_WEEK -> BodyTierBlue
    RecencyBucket.LAST_WEEK -> BodyTierGreen
    RecencyBucket.TWO_WEEKS_AGO -> BodyTierOrange
    RecencyBucket.OLDER, RecencyBucket.NEVER -> BodyTierIdleRed
}

/**
 * Compact corner badge for the card's top-right: "today", then Nd / Nw / Nmo /
 * Ny (e.g. 3d, 4w, 2mo, 1y). null → never played → no badge.
 */
fun ageMarkerLabel(
    lastPlayedAt: Long?,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val days = daysSince(lastPlayedAt, today, zone) ?: return null
    return when {
        days <= 0L -> "today"
        days < 7L -> "${days}d"
        days < 30L -> "${days / 7}w"
        days < 365L -> "${days / 30}mo"
        else -> "${days / 365}y"
    }
}
