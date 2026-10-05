package com.example.octo.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * v1 → v2 (Phase 5): transcript + body-part extraction state on [VideoEntity].
 *
 * ALTER TABLE ADD COLUMN only — no table rebuild, existing library rows (URLs, files,
 * sessions) are preserved untouched. Defaults:
 *  - `transcriptSource` NULL = never fetched (matches the Kotlin nullable default)
 *  - `analysisState` 'NONE' = fresh rows behave exactly like pre-v2 rows
 *  - `analyzedAt` NULL = never analyzed
 */
val MIGRATION_1_2: Migration = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE videos ADD COLUMN transcriptSource TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE videos ADD COLUMN analysisState TEXT NOT NULL DEFAULT 'NONE'")
        db.execSQL("ALTER TABLE videos ADD COLUMN analyzedAt INTEGER DEFAULT NULL")
    }
}

/**
 * v2 → v3 (Phase 22): explicit stretch confirmation on [SessionEntity].
 *
 * ALTER TABLE ADD COLUMN only — no table rebuild. Defaults:
 *  - `confirmed` 0 = every pre-v3 session stays PENDING until the user logs it via
 *    the review banners; nothing is recorded by mere playback anymore.
 *  - `creditedBodyPartsJson` NULL = no per-part snapshot yet (written at confirm time).
 */
val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN confirmed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN creditedBodyPartsJson TEXT DEFAULT NULL")
    }
}

/**
 * v3 → v4 (Phase 24): provenance of a video's body-part links on [VideoEntity].
 *
 * ALTER TABLE ADD COLUMN only — no table rebuild. `partSource` NULL = "unknown"
 * (every pre-v4 row: links came from the old pipeline, which never recorded HOW
 * it classified). New analyses write HEURISTIC or LLM; the Edit-parts dialog
 * writes MANUAL. Surfaced in the UI as "Quick scan" / "AI-analyzed" / "Edited".
 */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE videos ADD COLUMN partSource TEXT DEFAULT NULL")
    }
}
