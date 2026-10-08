package com.example.octo.ui.body

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.PathParser
import com.example.octo.R
import com.example.octo.data.local.Muscle
import kotlin.math.roundToInt

/**
 * The anatomical muscle figure ("Muscles" dashboard view), v13 poster-écorché:
 *
 *  - full-coverage muscle plates tile the body (no bare gaps) in poster-style
 *    hues — red pecs/lats, green abs, gold delts, violet triceps, blue
 *    quads/calves/glutes, orange forearms, rust biceps/hamstrings, salmon
 *    groin, teal shins, sand neck;
 *  - the SAME octopus head art as the octo-man composites over the dome, and
 *    the same per-side flip-centering offsets keep the figure on its axis;
 *  - stretched-today muscles glow teal over their poster hue.
 */
@Composable
fun MuscleFigureView(
    stretchedMuscles: Set<Muscle>,
    side: MuscleBodyGeometry.Side,
    onMuscleTap: (Muscle) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bodyPaths: List<Path> = remember {
        MuscleBodyGeometry.bodyShapes.mapNotNull {
            runCatching { PathParser.createPathFromPathData(it)?.asComposePath() }.getOrNull()
        }
    }
    val muscleRegions: List<Triple<Muscle, Path, String>> = remember(side) {
        MuscleBodyGeometry.musclesFor(side).mapNotNull { spec ->
            val muscle = Muscle.fromId(spec.muscleId) ?: return@mapNotNull null
            val path = runCatching { PathParser.createPathFromPathData(spec.pathData)?.asComposePath() }
                .getOrNull() ?: return@mapNotNull null
            Triple(muscle, path, spec.hue)
        }
    }
    val headBitmap: ImageBitmap = remember(side) {
        val res = if (side == MuscleBodyGeometry.Side.BACK) {
            R.drawable.octo_head_ppq_back
        } else {
            R.drawable.octo_head_ppq
        }
        BitmapFactory.decodeResource(context.resources, res).asImageBitmap()
    }
    val headArt = if (side == MuscleBodyGeometry.Side.BACK) BackHeadArt else FrontHeadArt

    Canvas(
        modifier = modifier
            .aspectRatio(1000f / 1500f)
            .pointerInput(side, stretchedMuscles) {
                detectTapGestures { tap ->
                    val sx = size.width / 1000f
                    val sy = size.height / 1500f
                    val vx = tap.x / sx
                    val vy = tap.y / sy
                    val hit = muscleRegions.lastOrNull { (_, path, _) -> spindleHit(path, vx, vy) }
                    if (hit != null) onMuscleTap(hit.first)
                }
            },
    ) {
        val sx = size.width / 1000f
        val sy = size.height / 1500f
        translate(left = -sideCenteringOffsetX(
            if (side == MuscleBodyGeometry.Side.BACK) {
                OctopusBodyGeometry.Side.BACK
            } else {
                OctopusBodyGeometry.Side.FRONT
            }
        )) {
            scale(sx, sy) {
                bodyPaths.forEach { path -> drawPath(path, color = Silhouette) }
                bodyPaths.forEach { path ->
                    drawPath(path, color = SilhouetteRim, style = Stroke(width = 3f))
                }

                val bodyClip = Path().apply { bodyPaths.forEach { addPath(it) } }
                clipPath(bodyClip) {
                    // Pass 1: dark contour under every plate (fills seams).
                    muscleRegions.forEach { (_, path, _) ->
                        val b = path.getBounds()
                        scale(1.03f, 1.03f, pivot = Offset(b.center.x, b.center.y)) {
                            drawPath(path, color = MuscleEdge, style = Stroke(width = 4f))
                        }
                    }
                    // Pass 2: poster-hue gradient bellies; stretched glow teal.
                    muscleRegions.forEach { (muscle, path, hue) ->
                        val stretched = muscle in stretchedMuscles
                        val (top, bottom) = hueShades(hue)
                        val bounds = path.getBounds()
                        val brush = Brush.verticalGradient(
                            colors = if (stretched) listOf(StretchTop, StretchBottom) else listOf(top, bottom),
                            startY = bounds.top,
                            endY = bounds.bottom,
                        )
                        if (stretched) {
                            drawPath(path = path, color = StretchGlow, style = Stroke(width = 9f))
                        }
                        drawPath(path = path, brush = brush)
                        drawPath(
                            path = path,
                            color = if (stretched) StretchEdge else HueEdge,
                            style = Stroke(width = 2f),
                        )
                    }
                }

                // Octopus head art (identical placement to the octo-man).
                val hs = HEAD_DOME_H * HEAD_MARGIN / headArt.domeH
                drawImage(
                    image = headBitmap,
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        (HEAD_CENTER_X - headArt.domeCx * hs).roundToInt(),
                        (HEAD_DOME_CY - headArt.domeCy * hs).roundToInt(),
                    ),
                    dstSize = androidx.compose.ui.unit.IntSize(
                        (headArt.w * hs).roundToInt(),
                        (headArt.h * hs).roundToInt(),
                    ),
                )
            }
        }
    }
}

/** Inscribed-ellipse hit test. */
private fun spindleHit(path: Path, x: Float, y: Float): Boolean {
    val b = path.getBounds()
    if (x < b.left || x > b.right || y < b.top || y > b.bottom) return false
    val rx = b.width / 2f
    val ry = b.height / 2f
    if (rx <= 0f || ry <= 0f) return false
    val nx = (x - b.center.x) / rx
    val ny = (y - b.center.y) / ry
    return nx * nx + ny * ny <= 1.2f
}

// ── Poster palette (deep-sea adapted) ──

private val Silhouette = Color(0xFF232E3C)
private val SilhouetteRim = Color(0xFF18202B)
private val MuscleEdge = Color(0xFF1E2836)
private val HueEdge = Color(0x55F2F6FF)

private val StretchTop = Color(0xFF66F2D6)
private val StretchBottom = Color(0xFF38BCA1)
private val StretchEdge = Color(0xFFD8FFF5)
private val StretchGlow = Color(0x7740D8BB)

/**
 * Idle shades: all muscles stay in the light periwinkle/lavender family so
 * unstretched-vs-stretched reads instantly; each group gets a subtle tint
 * drift within that family to keep neighboring plates distinguishable.
 */
private fun hueShades(hue: String): Pair<Color, Color> = when (hue) {
    "red" -> Color(0xFF9FA9D4) to Color(0xFF6B76A6)      // pecs, lats, traps — blue-leaning
    "rust" -> Color(0xFF9C9AD2) to Color(0xFF67679E)     // biceps, hamstrings — violet
    "gold" -> Color(0xFFA8A6D8) to Color(0xFF71709F)     // deltoids — light lavender
    "green" -> Color(0xFF96A4CE) to Color(0xFF62719E)    // abs, erectors — steel
    "violet" -> Color(0xFFA79BD6) to Color(0xFF72649E)   // triceps — purple
    "blue" -> Color(0xFF9BADCE) to Color(0xFF68789E)     // quads, calves, glutes
    "orange" -> Color(0xFFA3A0D0) to Color(0xFF6D6B9C)   // forearms
    "salmon" -> Color(0xFFA1A8D6) to Color(0xFF6D76A2)   // hip flexors, adductors
    "teal" -> Color(0xFF94AAD0) to Color(0xFF61739E)     // shins, soleus
    else -> Color(0xFFADAAD8) to Color(0xFF77749E)       // sand (neck)
}
