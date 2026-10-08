package com.example.octo.data.local

/** One row of [VideoBodyPartDao.videoCountsByPart]: videos tagged per body part. */
data class PartVideoCountRow(
    val bodyPartId: String,
    val videoCount: Int,
)
