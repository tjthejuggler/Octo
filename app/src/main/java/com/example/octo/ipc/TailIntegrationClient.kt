package com.example.octo.ipc

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Tail habit-app client — port of Wags' `HabitIntegrationRepository`
 * (docs/ARCHITECTURE.md §2, authoritative broadcast spec).
 *
 * Two channels exist in the protocol; Octo uses #1 (Phase 4):
 *  1. [ACTION_INCREMENT] — atomic `+sessions` / `+minutes` (protocol v3).
 *  2. ACTION_SET_HABIT_VALUES — idempotent per-date backfill (Phase 7, optional).
 *
 * All calls are fail-safe: Tail may be absent, in which case the session is still
 * saved locally and the UI shows a graceful "Tail not installed" feedback.
 */
class TailIntegrationClient(private val context: Context) {

    /** UI-facing outcome of a broadcast attempt. */
    enum class SendResult {
        /** Broadcast handed to the system without exception (delivery is fire-and-forget). */
        SENT,

        /** Tail absent or signature check failed — SecurityException on API 34+ (§2.6). */
        NO_TAIL,

        /** Anything else went wrong; session is still persisted locally. */
        ERROR,
    }

    /**
     * Sender slot identity (§2.7). [idKey]/[nameKey] are the SharedPreferences keys the
     * Phase 7 habit picker will use to persist the slot→habit mapping.
     */
    enum class Slot(val idKey: String, val nameKey: String, val label: String) {
        YOGA_STRETCH("habit_id_yoga", "habit_name_yoga", "Yoga / Stretching"),
    }

    /**
     * Queries Tail's habit list via its read-only provider. Returns names in Tail's
     * user-defined screen order; the name doubles as the stable habit ID (§2.4).
     * Empty list when Tail is not installed / the provider is unreachable.
     */
    fun queryHabits(): List<String> = try {
        val habits = mutableListOf<String>()
        context.contentResolver
            .query(HABITS_CONTENT_URI, arrayOf(COL_HABIT_NAME), null, null, null)
            ?.use { cursor ->
                val nameIdx = cursor.getColumnIndexOrThrow(COL_HABIT_NAME)
                while (cursor.moveToNext()) {
                    habits += cursor.getString(nameIdx)
                }
            }
        habits
    } catch (t: Throwable) {
        Log.i(TAG, "Tail habit discovery unavailable (Tail not installed?)", t)
        emptyList()
    }

    /**
     * Sends the protocol-v3 increment broadcast: ONE atomic `sessions + minutes` update
     * for [habitName] (§2.2). `sendBroadcast(intent, permission)` throws SecurityException
     * on API 34+ when Tail is absent — caught and mapped to [SendResult.NO_TAIL].
     */
    fun sendIncrement(
        habitName: String,
        minutes: Int,
        sessions: Int = 1,
        slot: Slot = Slot.YOGA_STRETCH,
    ): SendResult = try {
        val intent = Intent(ACTION_INCREMENT).apply {
            `package` = HABIT_APP_PACKAGE // explicit → reliable on API 26+
            putExtra(EXTRA_HABIT_ID, habitName) // String habit name — stable ID (§2.4)
            putExtra(EXTRA_SLOT, slot.name) // informational sender slot ("wags_slot")
            putExtra(EXTRA_MINUTES, minutes.coerceAtLeast(0))
            putExtra(EXTRA_SESSIONS, sessions.coerceAtLeast(0))
        }
        context.sendBroadcast(intent, PERMISSION_TAIL)
        Log.i(TAG, "Tail increment sent: habit=%s minutes=%d sessions=%d".format(habitName, minutes, sessions))
        SendResult.SENT
    } catch (e: SecurityException) {
        Log.i(TAG, "Tail not installed / permission not granted — session stays local", e)
        SendResult.NO_TAIL
    } catch (t: Throwable) {
        Log.w(TAG, "Tail increment broadcast failed", t)
        SendResult.ERROR
    }

    private companion object {
        const val TAG = "TailIntegration"

        // ── Exact constants from docs/ARCHITECTURE.md §2.1 (must match Wags/Tail) ──
        const val HABIT_APP_PACKAGE = "com.example.tail"

        /** Signature permission declared by Tail (protectionLevel="signature", §2.6). */
        const val PERMISSION_TAIL = "com.example.tail.permission.TAIL_INTEGRATION"

        val HABITS_CONTENT_URI: Uri = Uri.parse("content://com.example.tail.provider/habits")
        const val COL_HABIT_ID = "habit_id"
        const val COL_HABIT_NAME = "habit_name"

        const val ACTION_INCREMENT = "com.example.tail.ACTION_INCREMENT_HABIT"
        const val ACTION_SET_HABIT_VALUES = "com.example.tail.ACTION_SET_HABIT_VALUES"

        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"
        const val EXTRA_SLOT = "wags_slot"
        const val EXTRA_MINUTES = "EXTRA_MINUTES"
        const val EXTRA_SESSIONS = "EXTRA_SESSIONS"
        const val EXTRA_VALUES_JSON = "EXTRA_VALUES_JSON"
    }
}
