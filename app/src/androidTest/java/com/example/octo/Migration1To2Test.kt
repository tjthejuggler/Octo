package com.example.octo.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device validation of MIGRATION_1_2 (Phase 5): builds a REAL v1 database from the
 * exported schema DDL (app/schemas/.../1.json), inserts a row with pre-existing user
 * data, opens it through Room with the migration registered, and asserts that
 *  a) Room's schema validation passes (no "Migration didn't properly handle" crash),
 *  b) the old row survives intact,
 *  c) the new columns read back with their migration defaults.
 */
@RunWith(AndroidJUnit4::class)
class Migration1To2Test {

    private lateinit var context: Context
    private lateinit var dbFile: java.io.File

    /** Exact v1 DDL from app/schemas/com.example.octo.data.local.OctoDatabase/1.json. */
    private val v1Ddl = listOf(
        """
        CREATE TABLE IF NOT EXISTS `videos` (`id` TEXT NOT NULL, `title` TEXT NOT NULL,
        `channel` TEXT NOT NULL, `url` TEXT NOT NULL, `localFilePath` TEXT, `thumbnailPath` TEXT,
        `durationSeconds` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `downloadedAt` INTEGER,
        `downloadState` TEXT NOT NULL, `transcriptText` TEXT, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `video_body_part_cross_ref` (`videoId` TEXT NOT NULL,
        `bodyPartId` TEXT NOT NULL, PRIMARY KEY(`videoId`, `bodyPartId`))
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_video_body_part_cross_ref_bodyPartId` ON `${'$'}TABLE` (`bodyPartId`)".replace("${'$'}TABLE", "video_body_part_cross_ref"),
        """
        CREATE TABLE IF NOT EXISTS `sessions` (`autoId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
        `videoId` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `durationSeconds` INTEGER NOT NULL,
        `completed` INTEGER NOT NULL)
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_sessions_videoId` ON `sessions` (`videoId`)",
        """
        CREATE TABLE IF NOT EXISTS `body_part_stretch` (`date` TEXT NOT NULL,
        `bodyPartId` TEXT NOT NULL, `minutes` INTEGER NOT NULL, `sessions` INTEGER NOT NULL,
        PRIMARY KEY(`date`, `bodyPartId`))
        """.trimIndent(),
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("migration-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        // Build the v1 database with plain SQLite (no Room) + one user row.
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            v1Ddl.forEach(db::execSQL)
            // A real Phase-2..4 install has user_version = 1 stamped by Room. Without this
            // pragma Room treats the file as pre-packaged v0 and validates the v1 schema
            // against v2 entities — failing BEFORE the migration ever runs.
            db.version = 1
            db.insert(
                "videos",
                null,
                ContentValues().apply {
                    put("id", "inpok4MKVLM")
                    put("title", "5-Minute Meditation")
                    put("channel", "Goodful")
                    put("url", "https://www.youtube.com/watch?v=inpok4MKVLM")
                    put("durationSeconds", 317)
                    put("addedAt", 1_778_000_000_000)
                    put("downloadState", "COMPLETED")
                },
            )
            // Sanity: v1 must not have the new columns.
            val cols = db.rawQuery("PRAGMA table_info(videos)", null).use { c ->
                val out = mutableSetOf<String>()
                while (c.moveToNext()) out += c.getString(c.getColumnIndex("name"))
                out
            }
            assertFalse("analysisState must not exist in v1", cols.contains("analysisState"))
            db.close()
        }
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun migration1To2_preservesRow_andAddsColumnsWithDefaults() = runBlocking {
        // v1 fixture runs the FULL chain 1→2→3 (2→3 only adds session columns; the
        // videos-row assertions below are unaffected).
        val db = Room.databaseBuilder(context, OctoDatabase::class.java, "migration-test.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        try {
            val videoDao = db.videoDao()

            // Forces open + migration + Room schema validation.
            val row = videoDao.getById("inpok4MKVLM")
            assertNotNull("v1 row must survive the migration", row)
            assertEquals("5-Minute Meditation", row!!.title)
            assertEquals("Goodful", row.channel)
            assertEquals(317, row.durationSeconds)
            assertEquals(DownloadState.COMPLETED, row.downloadState)

            // New columns carry their migration defaults.
            assertEquals(AnalysisState.NONE, row.analysisState)
            assertNull(row.transcriptSource)
            assertNull(row.analyzedAt)
            assertNull(row.transcriptText)

            // New columns are writable (enum persisted, back readable).
            videoDao.updateTranscript("inpok4MKVLM", "breathe", TranscriptSource.CAPTIONS)
            videoDao.markAnalysisRunning("inpok4MKVLM")
            videoDao.updateAnalysisState("inpok4MKVLM", AnalysisState.DONE, 1_778_100_000_000)
            val updated = videoDao.getById("inpok4MKVLM")
            assertEquals("breathe", updated!!.transcriptText)
            assertEquals(TranscriptSource.CAPTIONS, updated.transcriptSource)
            assertEquals(AnalysisState.DONE, updated.analysisState)
            assertEquals(1_778_100_000_000L, updated.analyzedAt)

            // Cross-ref replace path used by the pipeline.
            db.videoBodyPartDao().replaceForVideo("inpok4MKVLM", setOf("core", "neck"))
            db.videoBodyPartDao().replaceForVideo("inpok4MKVLM", setOf("hamstrings"))
            val links = videoDao.getWithBodyParts("inpok4MKVLM")
            assertTrue(
                "replace must be idempotent: ${links?.bodyParts}",
                links?.bodyParts?.map { it.bodyPartId } == listOf("hamstrings"),
            )
        } finally {
            db.close()
        }
    }
}
