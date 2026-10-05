package com.example.octo.data.local

import androidx.room.Entity
import androidx.room.Index

/** N:M link video ↔ stretched body part. [bodyPartId] is a [BodyPart.id]. */
@Entity(
    tableName = "video_body_part_cross_ref",
    primaryKeys = ["videoId", "bodyPartId"],
    indices = [Index("bodyPartId")],
)
data class VideoBodyPartCrossRef(
    val videoId: String,
    val bodyPartId: String,
)
