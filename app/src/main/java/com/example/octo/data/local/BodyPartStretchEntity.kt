package com.example.octo.data.local

import androidx.room.Entity

/**
 * Daily minutes/sessions aggregate per body part — drives body-map coloring and stats.
 * Written by the session-recording worker (Phase 7) from SessionEntity + cross-refs.
 */
@Entity(tableName = "body_part_stretch", primaryKeys = ["date", "bodyPartId"])
data class BodyPartStretchEntity(
    /** Local date, ISO yyyy-MM-dd (matches Tail backfill protocol keys, §2.3). */
    val date: String,
    /** A [BodyPart.id]. */
    val bodyPartId: String,
    val minutes: Int,
    val sessions: Int,
)
