package com.example.octo.ui.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Custom octopus-themed bottom navigation bar (Phase 9).
 *
 * Design: a floating rounded deep-sea bar holding 4 hand-drawn Canvas icons —
 * an octopus-man silhouette (Body), a play triangle with a curled tentacle
 * (Library), tentacle bars (Stats) and a gear threaded by a tentacle
 * (Settings). Selection is signalled three ways for instant comprehension:
 * bioluminescent teal glow + suction-cup dot row + slight scale-up, plus the
 * text label under each icon. Fully accessible via semantics/role/contentDescription.
 */
@Composable
fun OctoBottomBar(
    destinations: List<OctoDestination>,
    currentRoute: String?,
    onNavigate: (OctoDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Box(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Phase 14: intrinsic height instead of a fixed 88.dp — the bar
                // now sizes to content (icon 44dp + dot row + label) with a
                // little breathing room, so labels can never overflow the
                // rounded clip no matter the font scale.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    destinations.forEach { destination ->
                        OctoNavItem(
                            destination = destination,
                            selected = currentRoute == destination.route,
                            onClick = { onNavigate(destination) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** One selectable octopus icon + label cell inside [OctoBottomBar]. */
@Composable
private fun OctoNavItem(
    destination: OctoDestination,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()

    // Animate every visual channel of the selection transition.
    val tint by animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "navTint",
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (selected) 0.30f else 0f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "navGlow",
    )
    val iconScale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.88f
            selected -> 1.12f
            else -> 1f
        },
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "navScale",
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0.65f,
        animationSpec = tween(250, easing = FastOutSlowInEasing),
        label = "navLabelAlpha",
    )

    val isSelected = selected
    val description = "${destination.label} tab" +
        if (isSelected) ", selected" else ""

    Column(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClickLabel = destination.label,
                role = Role.Tab,
                onClick = onClick,
            )
            .semantics {
                role = Role.Tab
                this.selected = isSelected
                contentDescription = description
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Soft teal halo + scaled icon.
        Box(
            modifier = Modifier.size(44.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (glowAlpha > 0.01f) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    tint.copy(alpha = glowAlpha),
                                    Color.Transparent,
                                ),
                            ),
                            shape = CircleShape,
                        ),
                )
            }
            Canvas(
                modifier = Modifier
                    .size(30.dp)
                    .scale(iconScale),
            ) {
                drawOctoIcon(destination, tint)
            }
        }

        Spacer(Modifier.height(2.dp))

        // Suction-cup dot row: a small octopus fingerprint marking the active tab.
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(3) { index ->
                val active = selected && index == 1
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .alpha(if (selected) 1f else 0.35f)
                        .background(
                            color = if (active) tint else tint.copy(alpha = 0.35f),
                            shape = CircleShape,
                        ),
                )
            }
        }

        Spacer(Modifier.height(1.dp))

        // Phase 14: the tight 13.sp lineHeight was clipping descenders
        // ("Library" y / "Settings" g). Default font line height + no wrap.
        Text(
            text = destination.label,
            fontSize = 11.sp,
            maxLines = 1,
            softWrap = false,
            color = tint,
            modifier = Modifier.alpha(labelAlpha),
        )
    }
}

/** Icon dispatch — each tab gets its own octopus-flavored Path drawing. */
private fun DrawScope.drawOctoIcon(destination: OctoDestination, tint: Color) {
    when (destination) {
        OctoDestination.BODY -> drawOctoMan(tint)
        OctoDestination.LIBRARY -> drawTentacledPlay(tint)
        OctoDestination.STATS -> drawTentacleBars(tint)
        OctoDestination.SETTINGS -> drawTentacleGear(tint)
    }
}

/**
 * BODY — tiny octopus-man: rounded head dome, two dot eyes, and 4 limbs that
 * read as both arms/legs and tentacles (curling tip on the right arm).
 */
private fun DrawScope.drawOctoMan(tint: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(width = w * 0.09f, cap = StrokeCap.Round)

    // Head dome (octopus mantle).
    val head = Path().apply {
        moveTo(w * 0.50f, h * 0.10f)
        cubicTo(w * 0.70f, h * 0.10f, w * 0.80f, h * 0.26f, w * 0.78f, h * 0.40f)
        cubicTo(w * 0.76f, h * 0.52f, w * 0.64f, h * 0.58f, w * 0.50f, h * 0.58f)
        cubicTo(w * 0.36f, h * 0.58f, w * 0.24f, h * 0.52f, w * 0.22f, h * 0.40f)
        cubicTo(w * 0.20f, h * 0.26f, w * 0.30f, h * 0.10f, w * 0.50f, h * 0.10f)
        close()
    }
    drawPath(head, color = tint, style = stroke)

    // Eyes.
    drawCircle(tint, radius = w * 0.035f, center = Offset(w * 0.42f, h * 0.34f))
    drawCircle(tint, radius = w * 0.035f, center = Offset(w * 0.58f, h * 0.34f))

    // Left arm: reaches down-left with a gentle curl.
    val leftArm = Path().apply {
        moveTo(w * 0.30f, h * 0.60f)
        cubicTo(w * 0.18f, h * 0.68f, w * 0.14f, h * 0.78f, w * 0.20f, h * 0.86f)
    }
    drawPath(leftArm, color = tint, style = stroke)

    // Right arm: tentacle with a curled tip (spiral hook).
    val rightArm = Path().apply {
        moveTo(w * 0.70f, h * 0.60f)
        cubicTo(w * 0.82f, h * 0.68f, w * 0.86f, h * 0.78f, w * 0.80f, h * 0.86f)
        cubicTo(w * 0.76f, h * 0.91f, w * 0.70f, h * 0.88f, w * 0.73f, h * 0.83f)
    }
    drawPath(rightArm, color = tint, style = stroke)

    // Legs.
    val leftLeg = Path().apply {
        moveTo(w * 0.44f, h * 0.60f)
        cubicTo(w * 0.42f, h * 0.72f, w * 0.40f, h * 0.82f, w * 0.38f, h * 0.90f)
    }
    drawPath(leftLeg, color = tint, style = stroke)
    val rightLeg = Path().apply {
        moveTo(w * 0.56f, h * 0.60f)
        cubicTo(w * 0.58f, h * 0.72f, w * 0.60f, h * 0.82f, w * 0.62f, h * 0.90f)
    }
    drawPath(rightLeg, color = tint, style = stroke)
}

/**
 * LIBRARY — a play triangle with a thin tentacle curling around it and ending
 * in a suction-cup dot.
 */
private fun DrawScope.drawTentacledPlay(tint: Color) {
    val w = size.width
    val h = size.height

    // Play triangle.
    val triangle = Path().apply {
        moveTo(w * 0.36f, h * 0.24f)
        lineTo(w * 0.36f, h * 0.76f)
        lineTo(w * 0.80f, h * 0.50f)
        close()
    }
    drawPath(triangle, color = tint, style = Stroke(width = w * 0.085f, cap = StrokeCap.Round))

    // Tentacle wrapping from the bottom-left, around the left edge, tip curling.
    val tentacle = Path().apply {
        moveTo(w * 0.10f, h * 0.90f)
        cubicTo(w * 0.06f, h * 0.60f, w * 0.16f, h * 0.20f, w * 0.38f, h * 0.12f)
        cubicTo(w * 0.50f, h * 0.08f, w * 0.52f, h * 0.22f, w * 0.42f, h * 0.24f)
    }
    drawPath(
        tentacle,
        color = tint,
        style = Stroke(width = w * 0.075f, cap = StrokeCap.Round),
    )

    // Suction cup at the tentacle tip.
    drawCircle(tint, radius = w * 0.035f, center = Offset(w * 0.42f, h * 0.24f))
}

/**
 * STATS — three "bars" that are actually small tentacles of different heights,
 * each with a curled tip; a dotted baseline anchors the chart.
 */
private fun DrawScope.drawTentacleBars(tint: Color) {
    val w = size.width
    val h = size.height
    val barWidth = w * 0.075f

    // Dotted baseline.
    drawLine(
        color = tint.copy(alpha = 0.45f),
        start = Offset(w * 0.12f, h * 0.90f),
        end = Offset(w * 0.88f, h * 0.90f),
        strokeWidth = w * 0.045f,
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 0.06f, w * 0.08f)),
    )

    // Bar 1 (short, straight).
    drawLine(
        color = tint,
        start = Offset(w * 0.24f, h * 0.90f),
        end = Offset(w * 0.24f, h * 0.58f),
        strokeWidth = barWidth,
        cap = StrokeCap.Round,
    )

    // Bar 2 (medium, tip curling right).
    val mid = Path().apply {
        moveTo(w * 0.50f, h * 0.90f)
        lineTo(w * 0.50f, h * 0.34f)
        cubicTo(w * 0.50f, h * 0.24f, w * 0.62f, h * 0.24f, w * 0.62f, h * 0.32f)
    }
    drawPath(mid, color = tint, style = Stroke(width = barWidth, cap = StrokeCap.Round))

    // Bar 3 (tall, tip curling left — classic tentacle hook).
    val tall = Path().apply {
        moveTo(w * 0.76f, h * 0.90f)
        lineTo(w * 0.76f, h * 0.18f)
        cubicTo(w * 0.76f, h * 0.06f, w * 0.60f, h * 0.06f, w * 0.62f, h * 0.16f)
    }
    drawPath(tall, color = tint, style = Stroke(width = barWidth, cap = StrokeCap.Round))

    // Suction cup on the tallest tentacle's curl.
    drawCircle(tint, radius = w * 0.035f, center = Offset(w * 0.635f, h * 0.155f))
}

/**
 * SETTINGS — a gear (circle + 8 teeth + center hole) with a tentacle threading
 * through the gear ring from below.
 */
private fun DrawScope.drawTentacleGear(tint: Color) {
    val w = size.width
    val h = size.height
    val center = Offset(w * 0.50f, h * 0.42f)
    val outerR = w * 0.26f
    val stroke = Stroke(width = w * 0.08f, cap = StrokeCap.Round)

    // Gear ring + hub.
    drawCircle(tint, radius = outerR, center = center, style = stroke)
    drawCircle(tint, radius = outerR * 0.38f, center = center, style = stroke)

    // 8 gear teeth as short radial strokes.
    repeat(8) { i ->
        val angle = Math.toRadians((i * 45).toDouble())
        val cosA = kotlin.math.cos(angle).toFloat()
        val sinA = kotlin.math.sin(angle).toFloat()
        drawLine(
            color = tint,
            start = Offset(center.x + cosA * outerR, center.y + sinA * outerR),
            end = Offset(center.x + cosA * outerR * 1.38f, center.y + sinA * outerR * 1.38f),
            strokeWidth = w * 0.075f,
            cap = StrokeCap.Round,
        )
    }

    // Tentacle threading through the ring: enters bottom-right, loops behind,
    // exits bottom-left with a curl.
    val tentacle = Path().apply {
        moveTo(w * 0.88f, h * 0.94f)
        cubicTo(w * 0.74f, h * 0.70f, w * 0.72f, h * 0.62f, center.x + outerR * 0.7f, center.y + outerR * 0.7f)
        cubicTo(w * 0.44f, h * 0.72f, w * 0.26f, h * 0.78f, w * 0.16f, h * 0.94f)
    }
    drawPath(
        tentacle,
        color = tint,
        style = Stroke(width = w * 0.07f, cap = StrokeCap.Round),
    )

    // Suction cup near the exit tip.
    drawCircle(tint, radius = w * 0.032f, center = Offset(w * 0.185f, h * 0.88f))
}
