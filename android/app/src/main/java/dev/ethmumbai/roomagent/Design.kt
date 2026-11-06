package dev.ethmumbai.roomagent

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/** Colour roles. One accent, spent on selection, progress and live state; everything else stays neutral. */
object C {
    val bg = Color(0xFF09090B)
    val surface = Color(0xFF111114)
    val raised = Color(0xFF18181C)
    val border = Color(0x14FFFFFF)
    val borderStrong = Color(0x26FFFFFF)
    val fg = Color(0xFFF5F5F6)
    val fg2 = Color(0xFFA1A1AA)
    val fg3 = Color(0xFF6E6E78)
    val accent = Color(0xFF8B7CFF)
    val accentSub = Color(0x228B7CFF)
    val ok = Color(0xFF3DD68C)
    val warn = Color(0xFFF5B544)
    val bad = Color(0xFFF2685F)
}

/** Type scale. Two weights only (400, 500); hierarchy comes from size and colour. */
object T {
    val display = TextStyle(fontSize = 42.sp, lineHeight = 44.sp, fontWeight = FontWeight.Medium, letterSpacing = (-1.6).sp, color = C.fg)
    val title = TextStyle(fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.7).sp, color = C.fg)
    val heading = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp, color = C.fg)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.15).sp, color = C.fg)
    val bodyDim = body.copy(color = C.fg2)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.05).sp, color = C.fg2)
    val mono = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = C.fg3)
    val huge = TextStyle(fontSize = 48.sp, lineHeight = 50.sp, fontWeight = FontWeight.Medium, letterSpacing = (-2.0).sp, color = C.fg)
    val num = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.8).sp, color = C.fg, fontFeatureSettings = "tnum")
}

fun Modifier.card(radius: Dp = 24.dp, bg: Color = C.surface, line: Color = C.border): Modifier {
    val s = RoundedCornerShape(radius)
    return this.background(bg, s).border(1.dp, line, s)
}

/** Press feedback: scales to 0.97 on a spring, light haptic on tap. */
fun Modifier.press(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && enabled) 0.97f else 1f, spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMedium), label = "press")
    val haptic = LocalHapticFeedback.current
    this.graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = src, indication = null, enabled = enabled) { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
}

/** One entrance per screen: content rises 14dp and fades in, staggered by [delayMs]. */
@Composable
fun Reveal(delayMs: Int = 0, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(delayMs.toLong()); p.animateTo(1f, tween(520, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) }
    Box(modifier.graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 14.dp.toPx() }) { content() }
}

enum class Btn { Primary, Secondary, Ghost }

@Composable
fun RoomButton(
    text: String, modifier: Modifier = Modifier, kind: Btn = Btn.Primary, enabled: Boolean = true,
    loading: Boolean = false, icon: ImageVector? = null, onClick: () -> Unit,
) {
    val (bg, fg, line) = when (kind) {
        Btn.Primary -> Triple(C.fg, C.bg, Color.Transparent)
        Btn.Secondary -> Triple(C.raised, C.fg, C.borderStrong)
        Btn.Ghost -> Triple(Color.Transparent, C.fg2, Color.Transparent)
    }
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.height(54.dp).alpha(if (enabled) 1f else 0.38f)
            .press(enabled && !loading, onClick).background(bg, shape).border(1.dp, line, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = fg, strokeWidth = 2.dp)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, null, Modifier.size(18.dp), tint = fg)
            Text(text, style = T.heading.copy(color = fg, fontSize = 16.sp))
        }
    }
}

@Composable
fun Chip(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.press(onClick = onClick)
            .background(if (selected) C.accentSub else C.raised, CircleShape)
            .border(1.dp, if (selected) C.accent.copy(alpha = 0.5f) else C.border, CircleShape)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) { Text(text, style = T.small.copy(color = if (selected) C.fg else C.fg2, fontWeight = FontWeight.Medium)) }
}

@Composable
fun PulseDot(color: Color, pulse: Boolean, size: Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "dot")
    val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearOutSlowInEasing)), label = "dotp")
    Canvas(Modifier.size(size * 2.6f)) {
        val c = center; val r = size.toPx() / 2
        if (pulse) drawCircle(color.copy(alpha = (1f - p.value) * 0.45f), r + r * 1.6f * p.value, c)
        drawCircle(color, r, c)
    }
}

@Composable
fun StatusPill(ok: Boolean, text: String) {
    Row(
        Modifier.background(C.surface, CircleShape).border(1.dp, C.border, CircleShape).padding(start = 6.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulseDot(if (ok) C.ok else C.bad, pulse = ok, size = 7.dp)
        Text(text, style = T.small.copy(color = C.fg, fontWeight = FontWeight.Medium))
    }
}

/** Four-bar signal meter from RSSI (dBm). */
@Composable
fun SignalBars(rssi: Int?, active: Boolean, modifier: Modifier = Modifier) {
    val level = if (!active || rssi == null) 0 else when { rssi > -55 -> 4; rssi > -67 -> 3; rssi > -78 -> 2; else -> 1 }
    Canvas(modifier.size(22.dp, 16.dp)) {
        val bw = 4.dp.toPx(); val gap = 2.dp.toPx()
        for (i in 0 until 4) {
            val h = size.height * (0.4f + 0.2f * i)
            drawRoundRect(
                if (i < level) C.fg else C.fg3.copy(alpha = 0.4f),
                Offset(i * (bw + gap), size.height - h), Size(bw, h), CornerRadius(1.5.dp.toPx()),
            )
        }
    }
}

/** Slow drifting colour field. Marketing backdrop only (landing and setup), never inside product UI. */
@Composable
fun Aurora(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "aurora")
    val a = t.animateFloat(0f, 1f, infiniteRepeatable(tween(15000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b = t.animateFloat(0f, 1f, infiniteRepeatable(tween(21000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val c1 = Offset(w * (0.15f + 0.6f * a.value), h * (0.12f + 0.12f * b.value))
        val c2 = Offset(w * (0.95f - 0.6f * b.value), h * (0.42f + 0.1f * a.value))
        drawCircle(Brush.radialGradient(listOf(C.accent.copy(alpha = 0.30f), Color.Transparent), c1, w), w, c1)
        drawCircle(Brush.radialGradient(listOf(Color(0xFF3B82F6).copy(alpha = 0.16f), Color.Transparent), c2, w * 0.8f), w * 0.8f, c2)
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, C.bg), startY = h * 0.35f, endY = h * 0.75f))
    }
}

/** Radar mark: still rings plus (optionally) rings that expand outward. */
@Composable
fun RadarMark(size: Dp, animate: Boolean = true, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "radar")
    val p = t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "rp")
    Canvas(modifier.size(size)) {
        val c = center; val R = this.size.minDimension / 2
        val hair = (this.size.minDimension / 140f).coerceAtLeast(1f)
        if (animate) for (i in 0 until 3) {
            val ph = (p.value + i / 3f) % 1f
            drawCircle(C.accent.copy(alpha = (1f - ph) * 0.55f), R * (0.3f + 0.7f * ph), c, style = Stroke(hair * 1.4f))
        }
        drawCircle(C.borderStrong, R * 0.62f, c, style = Stroke(hair))
        drawCircle(C.accent.copy(alpha = 0.35f), R * 0.42f, c, style = Stroke(hair * 1.4f))
        drawCircle(C.accent.copy(alpha = 0.18f), R * 0.3f, c)
        drawCircle(C.fg, R * 0.12f, c)
    }
}

/** Live audio strip. Bars ride two slow sine waves; they settle to a flat line when [active] is false. */
@Composable
fun Equalizer(active: Boolean, modifier: Modifier = Modifier, bars: Int = 44) {
    val t = rememberInfiniteTransition(label = "eq")
    val ph = t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "ph")
    val amp by animateFloatAsState(if (active) 1f else 0.06f, tween(600), label = "amp")
    Canvas(modifier) {
        val gap = 3.dp.toPx(); val bw = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val x = i.toFloat() / bars
            val env = sin(PI * x).toFloat().coerceAtLeast(0.15f)
            val v = (sin(ph.value * 1f + i * 0.55f) * 0.5f + 0.5f) * (sin(ph.value * 2f - i * 0.23f) * 0.5f + 0.5f)
            val h = (size.height * (0.1f + 0.9f * v * env * amp)).coerceAtLeast(3.dp.toPx())
            drawRoundRect(
                Brush.verticalGradient(listOf(C.accent, C.accent.copy(alpha = 0.35f)), startY = (size.height - h) / 2, endY = (size.height + h) / 2),
                Offset(i * (bw + gap), (size.height - h) / 2), Size(bw, h), CornerRadius(bw / 2),
            )
        }
    }
}

/** Headline with a slow highlight sweep. */
@Composable
fun ShimmerText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "shine")
    val x by t.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "x")
    Text(
        text, modifier,
        style = style.copy(brush = Brush.linearGradient(listOf(C.accent, Color(0xFFD9D4FF), C.accent), Offset(x * 700f, 0f), Offset(x * 700f + 360f, 80f))),
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(52.dp).background(C.raised, CircleShape).border(1.dp, C.border, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = C.fg2)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = T.heading)
        Spacer(Modifier.height(4.dp))
        Text(body, style = T.small, textAlign = TextAlign.Center)
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) { Box(modifier.fillMaxWidth().height(1.dp).background(C.border)) }
