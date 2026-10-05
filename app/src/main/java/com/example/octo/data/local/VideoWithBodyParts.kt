package com.example.octo.data.local

import androidx.room.Embedded
import androidx.room.Relation

/** Video row together with its body-part links (for library rows / detail view). */
data class VideoWithBodyParts(
    @Embedded val video: VideoEntity,
    @Relation(parentColumn = "id", entityColumn = "videoId")
    val bodyParts: List<VideoBodyPartCrossRef>,
)
