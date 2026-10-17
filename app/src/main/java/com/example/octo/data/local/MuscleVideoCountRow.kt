package com.example.octo.data.local

/** One row of [VideoMuscleDao.videoCountsByMuscle]: videos tagged per muscle. */
data class MuscleVideoCountRow(
    val muscleId: String,
    val videoCount: Int,
)
