package com.example.octo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.widget.RemoteViews
import com.example.octo.MainActivity
import com.example.octo.OctoApplication
import com.example.octo.R
import com.example.octo.data.local.BodyPart
import com.example.octo.ui.body.OctopusBodyGeometry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

/**
 * The Octo home-screen widget (1×2, front view): the octopus-man exactly as
 * the dashboard shows him (today's tier colors included), transparent
 * background, cropped to his alpha bounds — no card, no bounding box. Tapping
 * him opens the app's main screen.
 *
 * The figure ships to the launcher as a plain inlined Bitmap (scaled to stay
 * under the ~1 MB RemoteViews binder limit) — no content URIs, so it works on
 * every launcher without cross-package grants.
 *
 * Refresh triggers: widget add/resize (onUpdate / onAppWidgetOptionsChanged)
 * and every MainActivity resume (see [requestUpdate]) so logging a session in
 * the app recolors the widget immediately.
 */
class OctoWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        scope.launch {
            try {
                for (id in appWidgetIds) {
                    renderAndUpdate(context, appWidgetManager, id)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        scope.launch { renderAndUpdate(context, appWidgetManager, appWidgetId) }
    }

    companion object {
        /** Binder cap: 160k ARGB_8888 pixels ≈ 640 KB, safely under ~1 MB. */
        private const val MAX_PIXELS = 160_000

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Re-render every placed widget (called from MainActivity.onResume). */
        fun requestUpdate(context: Context) {
            val appContext = context.applicationContext
            val awm = AppWidgetManager.getInstance(appContext)
            val ids = awm.getAppWidgetIds(
                ComponentName(appContext, OctoWidgetProvider::class.java),
            )
            if (ids.isNotEmpty()) {
                scope.launch {
                    for (id in ids) renderAndUpdate(appContext, awm, id)
                }
            }
        }

        private suspend fun renderAndUpdate(
            context: Context,
            awm: AppWidgetManager,
            appWidgetId: Int,
        ) {
            val app = context.applicationContext as OctoApplication
            val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            val counts: Map<BodyPart, Int> = app.appContainer.database.bodyPartDao()
                .forDayOnce(today)
                .mapNotNull { row -> BodyPart.fromId(row.bodyPartId)?.to(row.sessions) }
                .toMap()

            // Render at the widget's real pixel size (min width × max height).
            val opts = awm.getAppWidgetOptions(appWidgetId)
            val density = context.resources.displayMetrics.density
            val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 60)
                .coerceAtLeast(30)
            val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 60)
                .coerceAtLeast(30)
            val wPx = (wDp * density).toInt().coerceAtLeast(60)
            val hPx = (hDp * density).toInt().coerceAtLeast(60)

            // The man only: cropped to his alpha bounds, transparent background
            // (no card/bounding box — the user wants the bare mascot).
            var figure = cropToContent(
                OctoWidgetRenderer.render(
                    context, OctopusBodyGeometry.Side.FRONT, counts, wPx, hPx,
                ),
            )
            if (figure.width * figure.height > MAX_PIXELS) {
                val k = sqrt(MAX_PIXELS.toDouble() / (figure.width * figure.height))
                figure = Bitmap.createScaledBitmap(
                    figure,
                    (figure.width * k).toInt().coerceAtLeast(1),
                    (figure.height * k).toInt().coerceAtLeast(1),
                    true,
                )
            }

            val rv = RemoteViews(context.packageName, R.layout.octo_widget)
            rv.setImageViewBitmap(R.id.octo_widget_image, figure)
            rv.setOnClickPendingIntent(R.id.octo_widget_image, launchPendingIntent(context))
            awm.updateAppWidget(appWidgetId, rv)
        }

        /** Crop the render to the mascot's alpha bounding box. */
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

        private fun launchPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
