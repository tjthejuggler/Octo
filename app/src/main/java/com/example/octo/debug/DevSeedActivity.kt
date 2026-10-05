package com.example.octo.debug

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.example.octo.BuildConfig
import com.example.octo.OctoApplication
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * DEBUG-ONLY adb hook (Phase 6 task spec's "adb-driven test session" option):
 *   adb shell am start -n com.example.octo/.debug.DevSeedActivity \
 *     --ei days 3 --ei parts_per_day 4
 * Writes deterministic BodyPartStretchEntity aggregates (the exact rows the
 * production SessionRepository.complete() pipeline writes) so the body map can be
 * verified without playing a video. A translucent trampoline: seeds, toasts, finishes.
 * BuildConfig.DEBUG guard → instantly finishes (no-op) in release builds.
 */
class DevSeedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i(TAG, "onCreate debug=${BuildConfig.DEBUG}")
        if (!BuildConfig.DEBUG) {
            android.util.Log.w(TAG, "dev seed ignored: not a debug build")
            finish()
            return
        }
        val days = intent.getIntExtra(EXTRA_DAYS, 3).coerceIn(1, 7)
        val partsPerDay = intent.getIntExtra(EXTRA_PARTS_PER_DAY, 4).coerceIn(1, 16)

        try {
            // Synchronous on the (invisible, short-lived) activity thread — a fresh
            // DB with a handful of upserts takes tens of ms; no scope/process races.
            val app = application as OctoApplication
            val dao = app.appContainer.database.bodyPartDao()
            val today = LocalDate.now()
            var rows = 0
            kotlinx.coroutines.runBlocking {
                for (d in 0 until days) {
                    val date = today.minusDays(d.toLong()).format(DateTimeFormatter.ISO_LOCAL_DATE)
                    val group = PART_GROUPS[d % PART_GROUPS.size]
                    for (p in 0 until partsPerDay) {
                        dao.increment(
                            date = date,
                            bodyPartId = group[p % group.size],
                            minutes = 3 + (d * 2) + p,
                            sessions = 1,
                        )
                        rows++
                    }
                }
            }
            android.util.Log.i(TAG, "seeded $rows aggregate rows over $days day(s)")
            Toast.makeText(this, "dev: seeded $rows rows 🐙", Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "seed failed", t)
        } finally {
            finish()
        }
    }

    companion object {
        private const val TAG = "DevSeed"
        const val EXTRA_DAYS = "days"
        const val EXTRA_PARTS_PER_DAY = "parts_per_day"

        private val PART_GROUPS = listOf(
            listOf("hamstrings", "lower_back", "calves", "quads"),
            listOf("neck", "shoulders", "upper_back", "chest"),
            listOf("core", "obliques", "hips_glutes", "lats"),
        )
    }
}
