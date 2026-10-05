package com.example.octo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.octo.data.local.MIGRATION_1_2
import com.example.octo.data.local.MIGRATION_2_3
import com.example.octo.data.local.OctoDatabase
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
 * On-device validation of MIGRATION_2_3 (Phase 22): builds a REAL v2 database from the
 * exported schema DDL (app/schemas/.../2.json), inserts a recorded session row, opens
 * it through Room with both migrations registered, and asserts that
 *  a) Room's schema validation passes,
 *  b) the old session row survives with confirmed = 0 (pending — nothing is auto-logged),
 *  c) the new columns are writable (confirm + snapshot round-trip).
 */
@RunWith(AndroidJUnit4::class)
class Migration2To3Test {

    private lateinit var context: Context
    private lateinit var dbFile: java.io.File

    /** Exact v2 DDL from app/schemas/com.example.octo.data.local.OctoDatabase/2.json. */
    private val v2Ddl = listOf(
        """
        CREATE TABLE IF NOT EXISTS `videos` (`id` TEXT NOT NULL, `title` TEXT NOT NULL,
        `channel` TEXT NOT NULL, `url` TEXT NOT NULL, `localFilePath` TEXT, `thumbnailPath` TEXT,
        `durationSeconds` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `downloadedAt` INTEGER,
        `downloadState` TEXT NOT NULL, `transcriptText` TEXT, `transcriptSource` TEXT,
        `analysisState` TEXT NOT NULL, `analyzedAt` INTEGER, PRIMARY KEY(`id`))
        """.trimIndent(),
        """
        CREATE TABLE IF NOT EXISTS `video_body_part_cross_ref` (`videoId` TEXT NOT NULL,
        `bodyPartId` TEXT NOT NULL, PRIMARY KEY(`videoId`, `bodyPartId`))
        """.trimIndent(),
        "CREATE INDEX IF NOT EXISTS `index_video_body_part_cross_ref_bodyPartId` ON `video_body_part_cross_ref` (`bodyPartId`)",
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
        dbFile = context.getDatabasePath("migration2to3-test.db")
        dbFile.parentFile?.mkdirs()
        dbFile.delete()

        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { db ->
            v2Ddl.forEach(db::execSQL)
            db.version = 2
            db.insert(
                "videos",
                null,
                ContentValues().apply {
                    put("id", "inpok4MKVLM")
                    put("title", "5-Minute Stretch")
                    put("channel", "Goodful")
                    put("url", "https://www.youtube.com/watch?v=inpok4MKVLM")
                    put("durationSeconds", 317)
                    put("addedAt", 1_778_000_000_000)
                    put("downloadState", "COMPLETED")
                    put("analysisState", "DONE")
                },
            )
            db.insert(
                "sessions",
                null,
                ContentValues().apply {
                    put("videoId", "inpok4MKVLM")
                    put("startedAt", 1_778_050_000_000)
                    put("durationSeconds", 300)
                    put("completed", 1)
                },
            )
            // Sanity: v2 must not have the new columns.
            val cols = db.rawQuery("PRAGMA table_info(sessions)", null).use { c ->
                val out = mutableSetOf<String>()
                while (c.moveToNext()) out += c.getString(c.getColumnIndex("name"))
                out
            }
            assertFalse("confirmed must not exist in v2", cols.contains("confirmed"))
            db.close()
        }
    }

    @After
    fun tearDown() {
        dbFile.delete()
    }

    @Test
    fun migration2To3_preservesSession_andAddsPendingDefaults() = runBlocking {
        val db = Room.databaseBuilder(context, OctoDatabase::class.java, "migration2to3-test.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        try {
            // Forces open + migration + Room schema validation. (One-shot suspend DAO
            // call — Room Flows stay active for invalidation tracking and never complete.)
            val session = db.sessionDao().byId(1L)
            assertNotNull("v2 session row must survive the migration", session)
            assertEquals(300, session!!.durationSeconds)
            assertTrue(session.completed)
            assertFalse("old sessions stay PENDING (nothing auto-logged)", session.confirmed)
            assertNull(session.creditedBodyParts)

            // New columns are writable: confirm + credited snapshot round-trip.
            assertEquals(1, db.sessionDao().confirmSession(session.autoId, "neck:5,core:5"))
            val confirmed = db.sessionDao().byId(session.autoId)!!
            assertTrue(confirmed.confirmed)
            assertEquals("neck:5,core:5", confirmed.creditedBodyParts)

            // Guard: re-confirm is a no-op (UPDATE ... WHERE confirmed = 0 hits 0 rows).
            assertEquals(0, db.sessionDao().confirmSession(session.autoId, "x:1"))
        } finally {
            db.close()
        }
    }

}
