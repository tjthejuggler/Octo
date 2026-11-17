package com.example.octo.widget

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import com.example.octo.OctoApplication
import com.example.octo.data.local.BodyPart
import com.example.octo.ui.body.OctopusBodyGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Read-only provider that serves the LIVE octopus-man figure to the Tail
 * app's combined scene widget (4x2). Same art the Octo home widget shows —
 * today's session tier colours, cropped to alpha bounds, transparent.
 *
 * Protocol (identical twin of Kenga's com.example.kenga.figure):
 *   URI      content://com.example.octo.figure/v1/figure
 *   Columns  figure  (Blob, PNG bytes; null when nothing rendered yet)
 *            hash    (Text, SHA-256 of the PNG; "empty" when no figure)
 *            updated (Long, epoch ms of the render)
 *   Access   readPermission = com.example.tail.permission.TAIL_INTEGRATION
 *            (signature-level; Tail already holds it — no new wiring)
 *
 * Cheap/hash-first contract: Tail queries the `hash` column first and only
 * opens the stream when it changed. Render state is memoized per day +
 * session fingerprint so repeat hash queries never touch the DB.
 */
class FigureSnapshotProvider : ContentProvider() {

    companion object {
        private const val TAG = "OctoFigureProvider"

        /** Binder-friendly figure budget: 160k ARGB px ≈ 640 KB (widget rule). */
        private const val MAX_PIXELS = 160_000

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // Last rendered figure state (memory mirror): (png, hash, updated).
        @Volatile
        private var cached: Triple<ByteArray, String, Long>? = null
        @Volatile
        private var cachedFingerprint: String? = null

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }

        /**
         * Re-renders the figure for today (if the session fingerprint
         * changed) and notifies Tail's scene widget. Called from
         * MainActivity.onResume alongside OctoWidgetProvider.requestUpdate.
         */
        fun refreshAndPing(context: Context) {
            val appContext = context.applicationContext
            scope.launch {
                try {
                    ensureFresh(appContext)
                    appContext.sendBroadcast(
                        Intent("com.example.tail.ACTION_SCENE_FIGURES_CHANGED")
                            .setPackage("com.example.tail"),
                        "com.example.tail.permission.TAIL_INTEGRATION"
                    )
                } catch (e: Exception) {
                    Log.i(TAG, "scene ping skipped (Tail absent?): ${e.message}")
                }
            }
        }

        /** Renders today's figure when the data fingerprint changed. */
        private suspend fun ensureFresh(context: Context) {
            val app = context.applicationContext as OctoApplication
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val counts: Map<BodyPart, Int> = app.appContainer.database.bodyPartDao()
                .forDayOnce(today)
                .mapNotNull { row -> BodyPart.fromId(row.bodyPartId)?.to(row.sessions) }
                .toMap()
            val fingerprint = today + ":" + counts.entries.sortedBy { it.key.id }
                .joinToString(",") { "${it.key.id}=${it.value}" }
            if (fingerprint == cachedFingerprint && cached != null) return

            val cropped = cropToContent(
                OctoWidgetRenderer.render(
                    context, OctopusBodyGeometry.Side.FRONT, counts,
                    900, 1350,
                )
            )
            val scaled = if (cropped.width * cropped.height > MAX_PIXELS) {
                val k = kotlin.math.sqrt(
                    MAX_PIXELS.toDouble() / (cropped.width * cropped.height)
                ).toFloat()
                Bitmap.createScaledBitmap(
                    cropped,
                    (cropped.width * k).toInt().coerceAtLeast(1),
                    (cropped.height * k).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                cropped
            }
            val bos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, bos)
            val bytes = bos.toByteArray()
            cached = Triple(bytes, sha256(bytes), System.currentTimeMillis())
            cachedFingerprint = fingerprint
        }

        /** Alpha-bounds crop (identical rule to OctoWidgetProvider). */
        private fun cropToContent(src: Bitmap): Bitmap {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            var left = w
            var top = h
            var right = -1
            var bottom = -1
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    if (px[row + x] ushr 24 != 0) {
                        if (x < left) left = x
                        if (x > right) right = x
                        if (y < top) top = y
                        if (y > bottom) bottom = y
                    }
                }
            }
            if (right < left || bottom < top) return src
            return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
        }
    }

    override fun onCreate(): Boolean = true

    /**
     * Byte-stream endpoint for contentResolver.openInputStream (Tail's
     * pull path). Serves the memoized PNG via a cache file — the
     * canonical ContentProvider way to hand out bytes.
     */
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (uri.path != "/v1/figure") {
            throw FileNotFoundException(uri.toString())
        }
        val ctx = context ?: throw FileNotFoundException("no context")
        if (mode != "r") throw FileNotFoundException("read-only provider")
        runBlocking { ensureFresh(ctx) }
        val bytes = cached?.first
            ?: throw FileNotFoundException("figure not rendered yet")
        val serve = File(ctx.cacheDir, "scene_figure_serve_octo.png")
        serve.writeBytes(bytes)
        return ParcelFileDescriptor.open(
            serve, ParcelFileDescriptor.MODE_READ_ONLY
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri.path != "/v1/figure") return null
        val ctx = context ?: return null
        // Blocking render for the query path: callers (Tail) are already on
        // IO dispatchers. The fingerprint memo makes repeat queries ~free.
        runBlocking { ensureFresh(ctx) }
        val state = cached
        val cols = projection ?: arrayOf("figure", "hash", "updated")
        val cursor = MatrixCursor(cols, 1)
        if (state == null) {
            // Nothing rendered yet (no sessions today): advertise empty.
            cursor.addRow(arrayOf(null, "empty", 0L))
            return cursor
        }
        val (bytes, hash, updated) = state
        val row = arrayOfNulls<Any>(cols.size)
        cols.forEachIndexed { i, col ->
            row[i] = when (col) {
                "figure" -> bytes
                "hash" -> hash
                "updated" -> updated
                else -> null
            }
        }
        cursor.addRow(row)
        return cursor
    }

    // Read-only provider: writes unsupported (Tail integration convention).
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read-only figure provider")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read-only figure provider")

    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("read-only figure provider")

    override fun getType(uri: Uri): String? = null
}
