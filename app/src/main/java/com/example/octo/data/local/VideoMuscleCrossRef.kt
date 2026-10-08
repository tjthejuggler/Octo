package com.example.octo.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * N:M link video ↔ stretched muscle. [muscleId] is a [Muscle.id].
 *
 * The muscle layer is ADDITIVE detail on top of the body-part system:
 * video_body_part_cross_ref stays the authoritative tagging set (library
 * filters, session crediting, dashboard coloring); this table only powers
 * the "exact muscles" body-screen listing. Deleting/rewriting it never
 * changes any credited minutes.
 */
@Entity(
    tableName = "video_muscle_cross_ref",
    primaryKeys = ["videoId", "muscleId"],
    indices = [Index("muscleId")],
)
data class VideoMuscleCrossRef(
    val videoId: String,
    val muscleId: String,
)
