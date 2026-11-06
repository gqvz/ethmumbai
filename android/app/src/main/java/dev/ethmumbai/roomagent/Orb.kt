package dev.ethmumbai.roomagent

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import kotlin.math.*

private val BLOBS = listOf(
    Color(0xFF7A4DFF), Color(0xFF00E5FF), Color(0xFFFF2DC7), Color(0xFF3D5BFF), Color(0xFFFF8A3D), Color(0xFF2DFFB5),
)
private val GLOW = Color(0xFF8A5CFF)

/**
 * The agent, as one living object. An organic iridescent orb: its outline wobbles on three sine harmonics,
 * five colour fields drift inside it, and rings leave it when it hears something.
 * [level] is standing energy, [pulseKey] fires a surge, [think] swirls it faster while a command is in flight.
 * The canvas is wider than the orb (radius = 30% of the width) so rings have room to travel.
 */
@Composable
fun Orb(level: Float, pulseKey: Int, modifier: Modifier = Modifier, think: Boolean = false) {
    val lvl by animateFloatAsState(level, tween(900), label = "orb-lvl")
    val thk by animateFloatAsState(if (think) 1f else 0f, tween(500), label = "orb-thk")
    val surge = remember { Animatable(0f) }
    LaunchedEffect(pulseKey) { surge.snapTo(1f); surge.animateTo(0f, tween(2600, easing = FastOutSlowInEasing)) }
    var t by remember { mutableFloatStateOf(0f) }
    var spin by remember { mutableFloatStateOf(0f) }
    var ringPh by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { n ->
            if (last != 0L) {
                val dt = ((n - last) / 1e9f).coerceAtMost(0.05f)
                val e = lvl + surge.value + thk
                t += dt * (0.5f + 1.3f * e)
                spin += dt * (0.12f + 1.6f * thk)
                ringPh += dt * (0.10f + 0.65f * (lvl + surge.value))
            }
            last = n
        }
    }
    var breathPh by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { n -> if (last != 0L) breathPh += ((n - last) / 1e9f).coerceAtMost(0.05f) * (1.1f + 1.8f * (lvl + surge.value)); last = n }
    }
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val c = center
        val e = (lvl + surge.value + thk * 0.6f).coerceIn(0f, 1.6f)
        // Breathing: a slow swell that quickens and deepens with energy, plus a pop on every event.
        val breath = 0.5f + 0.5f * sin(breathPh * 2f)
        val R = size.width * 0.30f * (1f + 0.045f * breath + 0.06f * e + 0.10f * surge.value)

        // Halo: soft coloured light that swells with the breath.
        val haloA = (0.30f + 0.28f * breath + 0.35f * e).coerceAtMost(0.95f)
        drawCircle(Brush.radialGradient(listOf(GLOW.copy(alpha = haloA), Color(0xFF00B7FF).copy(alpha = haloA * 0.35f), Color.Transparent), c, R * 1.66f), R * 1.66f, c)

        // Rings leaving the orb, tinted.
        val frac = ringPh - floor(ringPh)
        for (k in 0 until 4) {
            val p = (frac + k) / 4f
            val a = (1f - p).pow(2f) * 0.55f * (0.35f + e.coerceAtMost(1f))
            drawCircle(Color(0xFFB8A6FF).copy(alpha = a), R * (1.05f + p * 1.15f), c, style = Stroke(1.4.dp.toPx()))
        }
        // Flash ring on surge.
        if (surge.value > 0.02f) drawCircle(Color.White.copy(alpha = surge.value * 0.5f), R * (1.02f + (1f - surge.value) * 0.9f), c, style = Stroke(2.dp.toPx()))

        // Wobbling outline.
        val amp = 0.035f + 0.08f * e
        val path = Path()
        val n = 96
        for (i in 0..n) {
            val th = (2.0 * PI * i / n).toFloat()
            val r = R * (1f + amp * (0.5f * sin(2 * th + t * 1.1f) + 0.35f * sin(3 * th - t * 0.9f) + 0.2f * sin(5 * th + t * 1.7f)))
            val x = c.x + r * cos(th); val y = c.y + r * sin(th)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        clipPath(path) {
            drawRect(Brush.radialGradient(listOf(Color(0xFF2A1A7A), Color(0xFF0B0830)), c, R * 1.3f))
            BLOBS.forEachIndexed { i, col ->
                val sp = 0.6f + i * 0.21f
                val ang = t * sp * 0.6f + i * 1.3f + spin * (1f + i * 0.25f)
                val orbit = R * (0.40f + 0.08f * (i % 3) + 0.22f * e.coerceAtMost(1f))
                val bc = Offset(c.x + orbit * cos(ang), c.y + orbit * sin(ang * 0.83f + i))
                val br = R * (0.78f + 0.16f * sin(t * 0.7f + i) + 0.12f * breath)
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.68f), col.copy(alpha = 0.32f), col.copy(alpha = 0f)), bc, br), br, bc, blendMode = BlendMode.Plus)
            }
            // Bright core that pulses with the breath, specular highlight for volume.
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.10f + 0.16f * breath + 0.14f * e), Color.Transparent), c, R * 0.6f), R * 0.6f, c, blendMode = BlendMode.Plus)
            val hi = Offset(c.x - R * 0.34f, c.y - R * 0.42f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.6f), Color.Transparent), hi, R * 0.5f), R * 0.5f, hi)
            drawRect(Brush.radialGradient(colorStops = arrayOf(0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.28f)), center = hi, radius = R * 2.1f))
        }
        drawPath(
            path,
            Brush.sweepGradient(listOf(Color(0xFFE0D4FF).copy(alpha = 0.85f), Color(0xFF7AF0FF).copy(alpha = 0.25f), Color(0xFFFF9CE8).copy(alpha = 0.7f), Color(0xFF7AF0FF).copy(alpha = 0.25f), Color(0xFFE0D4FF).copy(alpha = 0.85f)), c),
            style = Stroke(1.6.dp.toPx()),
        )
    }
}
