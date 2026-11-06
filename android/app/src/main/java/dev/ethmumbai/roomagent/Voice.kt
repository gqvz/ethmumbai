package dev.ethmumbai.roomagent

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.*

/**
 * Voice field. Ported from two Originkit shaders:
 *  - Breathing Dots: hex-packed dots on a rounded square wave (atan(sin/delta)), phase lagging with an
 *    octagonal distance so the pulse travels outward, plus an RGB-split fringe from three offset copies.
 *  - Sonar Bloom: rings expand from a source, widening and fading as they travel.
 * [level] (0..1) is the standing energy (conversation in progress); [pulseKey] changing fires a surge, i.e. "I just heard something".
 */
@Composable
fun VoiceField(level: Float, pulseKey: Int, modifier: Modifier = Modifier, orb: Boolean = false, tint: Color = C.accent) {
    val lvl by animateFloatAsState(level, tween(700), label = "lvl")
    val surge = remember { Animatable(0f) }
    LaunchedEffect(pulseKey) { surge.snapTo(1f); surge.animateTo(0f, tween(2200, easing = FastOutSlowInEasing)) }
    var phase by remember { mutableFloatStateOf(0f) }
    var ringPh by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) withFrameNanos { n ->
            if (last != 0L) {
                val dt = ((n - last) / 1e9f).coerceAtMost(0.05f)
                val e = lvl + surge.value
                phase = (phase + dt * (0.10f + 0.30f * e)) % 1f
                ringPh += dt * (0.16f + 0.85f * e)
            }
            last = n
        }
    }
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val w = size.width; val h = size.height
        val e = (lvl + surge.value).coerceIn(0f, 1.5f)
        val cell = (if (orb) 11.dp else 15.dp).toPx()
        val cols = (w / cell).toInt() or 1
        val rows = (h / cell).toInt() or 1
        val cx = w / 2f; val cy = h / 2f
        val amp = 0.09f + 0.15f * e
        val maxD = max(cols, rows) / 2f
        val rOrb = min(cols, rows) / 2f
        val ph = phase

        // Sonar rings first, so the dots ride on top of them.
        val src = if (orb) Offset(cx, cy) else Offset(cx, h * 1.12f)
        val spread = if (orb) min(w, h) * 0.62f else h * 1.55f
        val frac = ringPh - floor(ringPh)
        val clip = androidx.compose.ui.graphics.Path().apply {
            if (orb) addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), min(w, h) / 2f)) else addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
        }
        clipPath(clip) {
        for (k in 0 until 6) {
            val p = (frac + k) / 6f
            val r = p * spread
            if (r < 3f) continue
            val wd = (0.02f + 0.30f * p) * (if (orb) min(w, h) else h) * 0.5f
            val a = (1f - p).pow(1.5f) * 0.8f * (0.35f + 0.65f * e.coerceAtMost(1f))
            val outer = r + wd * 2f
            drawCircle(
                Brush.radialGradient(
                    colorStops = arrayOf(0f to tint.copy(alpha = 0f), (max(r - wd, 0f) / outer) to tint.copy(alpha = 0f), (r / outer) to tint.copy(alpha = a * 0.42f), 1f to tint.copy(alpha = 0f)),
                    center = src, radius = outer,
                ),
                outer, src,
            )
        }
        }

        // Dots, three channel copies drawn additively with a small lag between them.
        for (lane in 0 until 3) {
            val mask = Color(if (lane == 0) tint.red else 0f, if (lane == 1) tint.green else 0f, if (lane == 2) tint.blue else 0f)
            val shift = lane * 0.016f * (0.5f + e)
            for (j in 0 until rows) for (i in 0 until cols) {
                val hsh = ((i * 73856093) xor (j * 19349663)) and 0xff
                val px = (i - (cols - 1) / 2f) + (hsh / 255f - 0.5f) * 0.3f
                val py = (j - (rows - 1) / 2f) + ((i % 2) - 0.5f) * 0.5f + (((hsh * 7) and 0xff) / 255f - 0.5f) * 0.3f
                val len = sqrt(px * px + py * py)
                val dirx = if (len > 1e-4f) px / len else 1f
                val dist = len + cos(acos(dirx.coerceIn(-1f, 1f)) * 8f) * 0.5f
                val delta = 0.15f + 0.2f * dist / maxD
                val wave = (2f * amp / PI.toFloat()) * atan(sin(2f * PI.toFloat() * (ph - shift - dist * 0.045f)) / delta)
                val s = 1f + wave
                val sx = cx + px * cell * s; val sy = cy + py * cell * s
                val f = if (orb) 1f - smooth(0.55f, 1f, len / rOrb)
                        else min(1f, min(min(sx, w - sx) / (w * 0.16f), min(sy, h - sy) / (h * 0.28f))).coerceAtLeast(0f)
                if (f <= 0.02f) continue
                val rad = cell * 0.2f * (1f + wave / amp * 0.55f).coerceAtLeast(0.3f) * (0.4f + 0.6f * f)
                drawCircle(mask.copy(alpha = f * (0.5f + 0.5f * (0.5f + 0.5f * wave / amp))), rad, Offset(sx, sy), blendMode = BlendMode.Plus)
            }
        }
        if (!orb) {
            // Feather the field into the card: erase the edges so the rings never show a hard rectangle.
            drawRect(Brush.horizontalGradient(0f to Color.Black, 0.16f to Color.Transparent, 0.84f to Color.Transparent, 1f to Color.Black), blendMode = BlendMode.DstOut)
            drawRect(Brush.verticalGradient(0f to Color.Black, 0.2f to Color.Transparent), blendMode = BlendMode.DstOut)
        }
    }
}

private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

private const val GLYPHS = "abcdefghijklmnopqrstuvwxyz0123456789"

/** Scramble-decode reveal (after Originkit's scramble/text-decode family): characters resolve left to right whenever the text changes. */
@Composable
fun DecodeText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(text) }
    LaunchedEffect(text) {
        val steps = 16
        for (s in 0 until steps) {
            val reveal = text.length * s / steps
            shown = buildString { text.forEachIndexed { i, c -> append(if (i < reveal || !c.isLetterOrDigit()) c else GLYPHS.random()) } }
            delay(34)
        }
        shown = text
    }
    Text(shown, modifier, style = style)
}
