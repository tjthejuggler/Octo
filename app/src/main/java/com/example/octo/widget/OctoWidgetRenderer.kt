package com.example.octo.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.PathParser
import com.example.octo.R
import com.example.octo.data.local.BodyPart
import com.example.octo.ui.body.BackHeadArt
import com.example.octo.ui.body.BorderIdle
import com.example.octo.ui.body.Cup
import com.example.octo.ui.body.FrontHeadArt
import com.example.octo.ui.body.GLOW_LAYERS
import com.example.octo.ui.body.HEAD_CENTER_X
import com.example.octo.ui.body.HEAD_DOME_CY
import com.example.octo.ui.body.HEAD_DOME_H
import com.example.octo.ui.body.HEAD_MARGIN
import com.example.octo.ui.body.MuscleLine
import com.example.octo.ui.body.OctopusBodyGeometry
import com.example.octo.ui.body.TentacleDeco
import com.example.octo.ui.body.sideCenteringOffsetX
import com.example.octo.ui.body.todayTierColor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Home-screen widget renderer: the octopus-man EXACTLY as the dashboard draws
 * him (same [OctopusBodyGeometry] paths, same tier colors from
 * [todayTierColor], same PPQ head-art compositing and flip-centering offset),
 * rendered offscreen to a Bitmap via [CanvasDrawScope] — no composition
 * required. Dashboard-only mode: no muscle overlay, no preview; the pulse
 * animation is frozen at full brightness (pulse = 1f).
 *
 * Draw steps mirror [com.example.octo.ui.body.OctopusBodyView]'s private
 * drawBody pipeline 1:1 (dashboard branch); keep them in sync if that
 * pipeline changes.
 */
object OctoWidgetRenderer {

    /** Suction cups below the head zone belong to the limbs (dashboard rule). */
    private const val HEAD_ART_BOTTOM_ROW = 700f

    fun render(
        context: Context,
        side: OctopusBodyGeometry.Side,
        todayCounts: Map<BodyPart, Int>,
        widthPx: Int,
        heightPx: Int,
    ): Bitmap {
        val scale = min(
            widthPx / OctopusBodyGeometry.CANVAS_WIDTH,
            heightPx / OctopusBodyGeometry.CANVAS_HEIGHT,
        ).coerceAtLeast(0.05f)
        val w = (OctopusBodyGeometry.CANVAS_WIDTH * scale).roundToInt().coerceAtLeast(1)
        val h = (OctopusBodyGeometry.CANVAS_HEIGHT * scale).roundToInt().coerceAtLeast(1)

        val image = ImageBitmap(w, h)
        val headRes = if (side == OctopusBodyGeometry.Side.BACK) {
            R.drawable.octo_head_ppq_back
        } else {
            R.drawable.octo_head_ppq
        }
        val head: ImageBitmap =
            BitmapFactory.decodeResource(context.resources, headRes).asImageBitmap()
        val headArt = if (side == OctopusBodyGeometry.Side.BACK) BackHeadArt else FrontHeadArt

        val regions: List<Pair<OctopusBodyGeometry.RegionSpec, androidx.compose.ui.graphics.Path>> =
            OctopusBodyGeometry.regionsFor(side).mapNotNull { spec ->
                PathParser.createPathFromPathData(spec.pathData)
                    ?.asComposePath()
                    ?.let { spec to it }
            }
        val fingerTentacles = OctopusBodyGeometry.fingerTentacles.mapNotNull {
            PathParser.createPathFromPathData(it.pathData)?.asComposePath()
        }
        val muscleLines = OctopusBodyGeometry.muscleLinesFor(side).mapNotNull {
            PathParser.createPathFromPathData(it.pathData)?.asComposePath()
        }
        val limbCups = OctopusBodyGeometry.suctionCups.filter { it.cy >= HEAD_ART_BOTTOM_ROW }
        val centeringOffsetX = sideCenteringOffsetX(side)
        val pulse = 1f

        CanvasDrawScope().draw(
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(image),
            size = Size(w.toFloat(), h.toFloat()),
        ) {
            // Pivot MUST be top-left: DrawScope.scale defaults to the canvas
            // CENTER, which at widget scale shoves the whole drawing right/down
            // and clips his right side + legs (the dashboard's aspect-matched
            // canvas + calibrated centering constants hide the same shift).
            scale(scale, scale, pivot = Offset.Zero) {
                translate(left = -centeringOffsetX) {
                    for ((spec, path) in regions) {
                        val count = todayCounts[spec.part] ?: 0
                        // Dashboard rule: the idle neck dome is hidden under the
                        // PPQ head art (fill only when active, for the glow).
                        if (spec.part == BodyPart.NECK && count == 0) continue
                        val color = todayTierColor(count)
                        val stretched = count > 0
                        if (stretched) {
                            repeat(GLOW_LAYERS) { i ->
                                drawPath(
                                    path = path,
                                    color = color.copy(alpha = 0.30f * pulse * (1f - i * 0.33f)),
                                    style = Stroke(width = 4f + i * 5f, cap = StrokeCap.Round),
                                )
                            }
                        }
                        drawPath(path = path, color = color)
                        drawPath(
                            path = path,
                            color = if (stretched) Color.White.copy(alpha = 0.55f) else BorderIdle,
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                        )
                    }
                    for (t in fingerTentacles) {
                        drawPath(path = t, color = TentacleDeco)
                        drawPath(
                            path = t,
                            color = BorderIdle,
                            style = Stroke(width = 2.5f, cap = StrokeCap.Round),
                        )
                    }
                    for (line in muscleLines) {
                        drawPath(line, MuscleLine, style = Stroke(width = 3f, cap = StrokeCap.Round))
                    }
                    for (c in limbCups) {
                        drawCircle(Cup, c.radius, Offset(c.cx, c.cy))
                    }
                    // PPQ head art over the dome (uniform scale, dome-center fit).
                    val s = HEAD_DOME_H * HEAD_MARGIN / headArt.domeH
                    drawImage(
                        image = head,
                        dstOffset = IntOffset(
                            (HEAD_CENTER_X - headArt.domeCx * s).roundToInt(),
                            (HEAD_DOME_CY - headArt.domeCy * s).roundToInt(),
                        ),
                        dstSize = IntSize(
                            (headArt.w * s).roundToInt(),
                            (headArt.h * s).roundToInt(),
                        ),
                    )
                    // Neck glow ring re-traced OVER the art (dashboard rule).
                    if ((todayCounts[BodyPart.NECK] ?: 0) > 0) {
                        val neck = regions.firstOrNull { it.first.part == BodyPart.NECK }?.second
                        if (neck != null) {
                            val color = todayTierColor(todayCounts[BodyPart.NECK] ?: 0)
                            repeat(GLOW_LAYERS) { i ->
                                drawPath(
                                    path = neck,
                                    color = color.copy(alpha = 0.30f * pulse * (1f - i * 0.33f)),
                                    style = Stroke(width = 4f + i * 5f, cap = StrokeCap.Round),
                                )
                            }
                        }
                    }
                }
            }
        }
        return image.asAndroidBitmap()
    }
}
