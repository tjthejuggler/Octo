package com.example.octo.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.sin
import kotlin.random.Random

/** Deep→shallow gradient stops, themed. */
private data class OctoWaterColors(
    val deep: Color,
    val mid: Color,
    val shallow: Color,
    val ray: Color,
    val bubble: Color,
)

private val DarkWater = OctoWaterColors(
    deep = Color(0xFF04141A), // abyss floor
    mid = Color(0xFF081C22), // current background
    shallow = Color(0xFF0E2E38), // faint aqua glow up top
    ray = Color(0xFF82D9CD), // brand teal, low alpha shafts
    bubble = Color(0xFFB3F1E8),
)

private val LightWater = OctoWaterColors(
    deep = Color(0xFFD8E8E6),
    mid = Color(0xFFEDF5F3),
    shallow = Color(0xFFBDE7E0),
    ray = Color(0xFF6FC7BA),
    bubble = Color(0xFF6FC7BA),
)

/**
 * Ambient underwater backdrop for the whole app: a vertical deep→shallow
 * gradient, two soft diagonal light shafts, and a handful of drifting bubbles.
 * Pure Canvas — resolution-independent, zero asset weight, ~free to draw.
 * Content goes in [content]; screens must use transparent containers above it.
 */
@Composable
fun OctoBackground(darkTheme: Boolean, modifier: Modifier = Modifier) {
    val water = if (darkTheme) DarkWater else LightWater
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    // Deterministic bubble field so it doesn't reshuffle every recomposition.
    val bubbles = remember(darkTheme) {
        List(10) {
            Bubbles(
                xFrac = Random(100 + it).nextFloat(),
                yFrac = Random(200 + it).nextFloat(),
                radiusDp = 2f + Random(300 + it).nextFloat() * 5f,
                alpha = 0.06f + Random(400 + it).nextFloat() * 0.10f,
                speed = 0.5f + Random(500 + it).nextFloat(),
            )
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(water.shallow, water.mid, water.deep),
                    startY = 0f,
                    endY = h,
                ),
            )

            // Two wide diagonal light shafts from the surface.
            val shaftWidth = w * 0.28f
            listOf(-0.15f, 0.45f).forEachIndexed { i, xFrac ->
                rotate(degrees = if (i == 0) 14f else -10f, pivot = Offset(w * xFrac + shaftWidth / 2, 0f)) {
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(water.ray.copy(alpha = if (darkTheme) 0.05f else 0.08f), Color.Transparent),
                            startY = 0f,
                            endY = h * 0.85f,
                        ),
                        topLeft = Offset(w * xFrac, 0f),
                        size = Size(shaftWidth, h),
                    )
                }
            }

            // A couple of very soft caustic ripples near the top.
            repeat(3) { i ->
                val path = Path()
                val baseY = h * (0.06f + i * 0.05f)
                val amp = h * 0.012f
                path.moveTo(0f, baseY)
                var x = 0f
                while (x <= w) {
                    path.lineTo(x, baseY + sin(x / w * 6f + i * 2f) * amp)
                    x += w / 24f
                }
                drawPath(
                    path,
                    color = water.ray.copy(alpha = if (darkTheme) 0.04f else 0.06f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 2.dp.toPx() }),
                )
            }

            // Drifting bubbles (static field; the ambient motion lives in the app, not here).
            bubbles.forEach { b ->
                val layoutAwareX = if (layoutDirection == LayoutDirection.Rtl) 1f - b.xFrac else b.xFrac
                drawCircle(
                    color = water.bubble.copy(alpha = b.alpha),
                    radius = with(density) { b.radiusDp.dp.toPx() },
                    center = Offset(layoutAwareX * w, b.yFrac * h),
                )
            }
        }
    }
}

private data class Bubbles(val xFrac: Float, val yFrac: Float, val radiusDp: Float, val alpha: Float, val speed: Float)
