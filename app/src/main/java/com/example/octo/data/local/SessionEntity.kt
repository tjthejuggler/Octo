package com.example.octo.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One playback session (started → completed/paused). Recorded by the player (Phase 4). */
@Entity(tableName = "sessions", indices = [Index("videoId")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val autoId: Long = 0,
    val videoId: String,
    val startedAt: Long, // epoch millis
    val durationSeconds: Int,
    val completed: Boolean, // true = finished/“mark done”, false = abandoned mid-way
    /**
     * Phase 22: explicit stretch confirmation. Playing a video records NOTHING until
     * the user confirms “Yes, log it” — unconfirmed sessions are invisible to Body /
     * Stats / streak (they never touch [BodyPartStretchEntity]) and send no Tail broadcast.
     */
    val confirmed: Boolean = false,
    /**
     * Snapshot `partId:minutes,partId:minutes` of the per-part minutes credited to the
     * daily aggregates AT CONFIRM TIME — lets undo subtract exactly this session's
     * contribution even if later sessions changed the totals.
     */
    @androidx.room.ColumnInfo(name = "creditedBodyPartsJson")
    val creditedBodyParts: String? = null,
)
