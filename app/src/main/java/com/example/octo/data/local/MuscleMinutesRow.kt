package com.example.octo.data.local

/** One row of [VideoMuscleDao.muscleMinutesInRange]: confirmed-session minutes per muscle. */
data class MuscleMinutesRow(
    val muscleId: String,
    val minutes: Int,
)
