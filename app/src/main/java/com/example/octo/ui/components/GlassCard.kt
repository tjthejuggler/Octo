package com.example.octo.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Semi-transparent "pane of sea glass": a foam-tinted wash over the water
 * background, a subtle top-edge highlight like light refracting through the
 * surface, and a faint border. Content sits on the water itself instead of an
 * opaque gray slab — keeping the app's fluid, transparent underwater feel.
 *
 * Dark theme: translucent teal-tinted pane (light passes through).
 * Light theme: frosted white pane.
 */
/** Pane flavor: [Light] frosted foam, [Deep] dark hazy sea glass (readable w/ light text). */
enum class GlassTone { Light, Deep }

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    /** Opacity 0..1: 1f = standard sea glass, higher alphas = hazier/more opaque. */
    opacity: Float = 1f,
    tone: GlassTone = GlassTone.Light,
    content: @Composable () -> Unit,
) {
    val darkTheme = MaterialTheme.colorScheme.surface == AbyssSurfaceRef
    val useDeep = tone == GlassTone.Deep || darkTheme
    // Deep glass keeps a DARK base so light text stays readable when hazy;
    // Light glass is the airy foam wash used on the standard section cards.
    val paneBase = when {
        tone == GlassTone.Deep -> Color(0xFF0E2730) // AbyssSurface: dark teal glass
        useDeep -> FoamBright
        else -> Color.White
    }
    val baseAlpha = when {
        tone == GlassTone.Deep -> 0.93f
        darkTheme -> 0.07f
        else -> 0.55f
    }
    val container = paneBase.copy(alpha = (baseAlpha * opacity).coerceAtMost(0.97f))
    val highlight = paneBase.copy(alpha = ((if (tone == GlassTone.Deep) 0.10f else if (darkTheme) 0.16f else 0.8f)) * opacity)
    val border = paneBase.copy(alpha = ((if (tone == GlassTone.Deep) 0.22f else if (darkTheme) 0.14f else 0.6f)) * opacity)

    Card(
        modifier = modifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(1.dp, border),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .drawBehind {
                    // Top-edge light refraction, fading to clear.
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(highlight, Color.Transparent),
                            startY = 0f,
                            endY = size.height * 0.35f,
                        ),
                    )
                },
        ) {
            content()
        }
    }
}

// Local theme anchors (kept out of the global namespace deliberately).
private val AbyssSurfaceRef = Color(0xFF0E2730)
private val FoamBright = Color(0xFFCDEDE7)
