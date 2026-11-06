package dev.ethmumbai.roomagent

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max

private val fmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())
fun at(ts: Double): String = fmt.format(Date((ts * 1000).toLong()))
fun ago(ts: Double): String {
    val s = (System.currentTimeMillis() / 1000.0 - ts).toLong()
    return when { s < 60 -> "just now"; s < 3600 -> "${s / 60}m ago"; s < 86400 -> "${s / 3600}h ago"; else -> "${s / 86400}d ago" }
}

private val SUGGESTIONS = listOf("Summarize the last conversation", "I'm going into a conversation", "What did we decide last week?")

/** Circular reveal from the dock glyph that opened it. */
private class CircleReveal(val origin: Offset, val p: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = origin.x * size.width; val cy = origin.y * size.height
        val maxR = hypot(max(cx, size.width - cx), max(cy, size.height - cy))
        return Outline.Generic(Path().apply { addOval(Rect(Offset(cx, cy), maxR * p)) })
    }
}

@Composable
private fun RevealPage(open: Boolean, origin: Offset, content: @Composable () -> Unit) {
    val p by animateFloatAsState(if (open) 1f else 0f, spring(dampingRatio = 1f, stiffness = 120f), label = "page")
    if (p > 0.002f) Box(
        Modifier.fillMaxSize().graphicsLayer { clip = true; shape = CircleReveal(origin, p) }.background(C.bg)
            .pointerInput(Unit) { detectTapGestures { } },
    ) { Box(Modifier.graphicsLayer { alpha = (p * 1.7f - 0.35f).coerceIn(0f, 1f) }) { content() } }
}

@Composable
fun Main(onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val st by Repo.state.collectAsState()
    val err by Repo.error.collectAsState()
    val online = err == null
    var page by remember { mutableIntStateOf(0) }
    var asking by remember { mutableStateOf(false) }
    var thinking by remember { mutableStateOf(false) }
    var beat by remember { mutableIntStateOf(0) }
    var sent by remember { mutableStateOf<String?>(null) }
    val overview = remember { Animatable(0f) }
    val latest = st.notes.maxByOrNull { it.ts }
    val token = st.notes.size + st.convos.sumOf { it.lines.size } + beat
    val pending = st.reminders.count { !it.fired }
    val live = st.live?.takeIf { l -> latest == null || l.ts > latest.ts }?.text

    BackHandler(page != 0 || asking || overview.value > 0.4f) {
        when { page != 0 -> page = 0; asking -> asking = false; else -> scope.launch { overview.animateTo(0f, spring(stiffness = 200f)) } }
    }

    val recorder = remember { Recorder() }
    var listening by remember { mutableStateOf(false) }
    var micLevel by remember { mutableFloatStateOf(0f) }
    var heard by remember { mutableStateOf<String?>(null) }

    var sid by remember { mutableStateOf("") }
    var stream by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun finishVoice() {
        listening = false
        val job = stream; val id = sid
        scope.launch {
            thinking = true; sent = "working"
            job?.cancelAndJoin()
            val pcm = recorder.stop()
            val r = withContext(Dispatchers.IO) { Repo.voiceEnd(ctx, id, pcm) }
            when {
                r == null -> sent = "couldn't send"
                r.isBlank() -> { sent = "didn't catch that"; heard = null }
                else -> { heard = r; sent = "working" }
            }
            if (!r.isNullOrBlank()) repeat(6) { delay(900); withContext(Dispatchers.IO) { Repo.fetch(ctx) }; beat++ }
            else delay(2000)
            thinking = false; delay(800); sent = null; heard = null
        }
    }
    fun startVoice() {
        if (!recorder.start()) { sent = "mic unavailable"; return }
        listening = true; heard = null; sent = "listening"; sid = java.util.UUID.randomUUID().toString().take(8)
        val id = sid
        stream = scope.launch {
            while (true) {
                delay(700)
                val chunk = recorder.drain()
                val t = withContext(Dispatchers.IO) { Repo.voiceChunk(ctx, id, chunk) }
                if (!t.isNullOrBlank()) heard = t
            }
        }
    }
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) startVoice() }
    fun toggleVoice() {
        if (listening) finishVoice()
        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startVoice()
        else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(listening) {
        if (listening) {
            val t0 = System.currentTimeMillis()
            while (listening) { micLevel = recorder.level; if (System.currentTimeMillis() - t0 > 20000) { finishVoice(); break }; delay(60) }
        }
        micLevel = 0f
    }

    fun send(t: String) {
        asking = false
        scope.launch {
            thinking = true; sent = "sending"
            val ok = withContext(Dispatchers.IO) { Repo.sendCommand(ctx, t) }
            sent = if (ok) "sent" else "couldn't send"
            delay(2400); thinking = false; beat++
            withContext(Dispatchers.IO) { Repo.fetch(ctx) }
            delay(1600); sent = null
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(C.bg)) {
        val hPx = constraints.maxHeight.toFloat()
        val drag = rememberDraggableState { dy -> scope.launch { overview.snapTo((overview.value - dy / (hPx * 0.5f)).coerceIn(0f, 1f)) } }
        val dragStop: suspend (Float) -> Unit = { v ->
            val to = when { v < -700f -> 1f; v > 700f -> 0f; else -> if (overview.value > 0.5f) 1f else 0f }
            overview.animateTo(to, spring(dampingRatio = 0.9f, stiffness = 220f))
        }

        // The agent.
        Orb(
            level = if (!online) 0f else 0.28f + micLevel * 1.1f + (if (live != null) 0.45f else 0f), pulseKey = token,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).align(Alignment.TopCenter).offset(y = 44.dp).graphicsLayer {
                val o = overview.value
                scaleX = 1f - 0.5f * o; scaleY = 1f - 0.5f * o; translationY = -o * 200.dp.toPx(); alpha = if (online) 1f else 0.35f
            },
            think = thinking && !listening,
        )
        // Gesture layer: drag up anywhere on the stage for the overview.
        Box(Modifier.fillMaxSize().draggable(drag, Orientation.Vertical, onDragStopped = { dragStop(it) }))

        // Top line.
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 28.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("atlas", style = T.heading.copy(letterSpacing = 1.sp))
            Spacer(Modifier.weight(1f))
            PulseDot(if (online) C.ok else C.bad, online, 6.dp)
            Text(if (online) "online" else "offline", style = T.mono)
            Box(Modifier.size(48.dp).press(onClick = onSettings), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Settings, "Settings", Modifier.size(20.dp), tint = C.fg3) }
        }

        // One line: the last thing the room heard or did.
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 28.dp, end = 28.dp, bottom = 196.dp)
                .graphicsLayer { alpha = (1f - overview.value * 2.2f).coerceAtLeast(0f) },
        ) {
            Text(
                when { !online -> "offline"; sent != null -> sent!!; live != null -> "hearing…"; latest != null -> "${ago(latest.ts)} · ${latest.source}"; else -> "listening" },
                style = T.mono,
            )
            Spacer(Modifier.height(8.dp))
            DecodeText(
                when { heard != null -> heard!!; listening -> "Listening…"; live != null && sent == null -> live; sent == "working" -> "…"; sent == "didn't catch that" -> "Didn't catch that."; !online -> "Reconnecting…"; latest != null -> latest.text; else -> "Say “hey Atlas”" },
                T.title.copy(fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-1.0).sp),
            )
            Spacer(Modifier.height(18.dp))
            val bob by rememberInfiniteTransition(label = "bob").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
            Row(verticalAlignment = Alignment.CenterVertically) {
              Row(Modifier.graphicsLayer { translationY = -bob * 5.dp.toPx() }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.size(16.dp), tint = C.fg3)
                Text("overview", style = T.mono, modifier = Modifier.pressText { scope.launch { overview.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 220f)) } })
              }
              Spacer(Modifier.width(22.dp))
              Text("type", style = T.mono, modifier = Modifier.pressText { asking = true })
            }
        }

        // Dock and composer swap places.
        AnimatedVisibility(
            !asking && page == 0, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(260, 120)) + slideInVertically(spring(stiffness = 300f)) { it / 2 }, exit = fadeOut(tween(120)) + slideOutVertically(tween(180)) { it / 2 },
        ) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp, start = 36.dp, end = 36.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                DockGlyph(Icons.Filled.Notifications, pending > 0) { page = 1 }
                AskButton(listening, micLevel) { toggleVoice() }
                DockGlyph(Icons.Filled.ChatBubbleOutline, false) { page = 2 }
            }
        }
        AnimatedVisibility(
            asking, Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(tween(240)) + slideInVertically(spring(stiffness = 350f)) { it / 3 }, exit = fadeOut(tween(120)) + slideOutVertically(tween(160)) { it / 3 },
        ) { Composer(onSend = ::send, onCancel = { asking = false }) }

        // Overview: drag up from the stage.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.78f)
                .graphicsLayer { translationY = (1f - overview.value) * size.height }.background(C.bg),
        ) {
            Hairline()
            Box(Modifier.fillMaxWidth().height(40.dp).draggable(drag, Orientation.Vertical, onDragStopped = { dragStop(it) }), contentAlignment = Alignment.Center) {
                Box(Modifier.width(28.dp).height(2.dp).background(C.borderStrong))
            }
            Overview(st)
        }

        RevealPage(page == 1, Offset(0.12f, 0.93f)) { RemindersPage(st) { page = 0 } }
        RevealPage(page == 2, Offset(0.88f, 0.93f)) { ChatsPage(st) { page = 0 } }
    }
}

@Composable
private fun DockGlyph(icon: ImageVector, badge: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).press(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(26.dp), tint = C.fg2)
        if (badge) Box(Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp).size(7.dp).background(C.accent, CircleShape))
    }
}

@Composable
private fun AskButton(listening: Boolean, level: Float, onClick: () -> Unit) {
    val ring by rememberInfiniteTransition(label = "ask").animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearOutSlowInEasing)), label = "ring")
    Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = (1f - ring) * 0.28f), 36.dp.toPx() + 16.dp.toPx() * ring, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
            if (listening) drawCircle(C.accent.copy(alpha = 0.35f), 36.dp.toPx() + 14.dp.toPx() * level, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
        Box(Modifier.size(72.dp).press(onClick = onClick).background(if (listening) C.accent else C.fg, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (listening) Icons.Filled.Stop else Icons.Filled.Mic, if (listening) "Stop" else "Ask", Modifier.size(28.dp), tint = C.bg)
        }
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(120); focus.requestFocus() }
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, C.bg, C.bg))).imePadding().navigationBarsPadding()
            .padding(start = 28.dp, end = 28.dp, bottom = 16.dp, top = 40.dp),
    ) {
        SUGGESTIONS.forEachIndexed { i, s ->
            Reveal(i * 70) {
                Text(s, style = T.bodyDim.copy(fontSize = 17.sp), modifier = Modifier.padding(vertical = 9.dp).pressText { onSend(s) })
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    if (text.isEmpty()) Text("Ask Atlas…", style = T.heading.copy(color = C.fg3, fontSize = 22.sp))
                    BasicTextField(
                        text, { text = it }, singleLine = true, textStyle = T.heading.copy(fontSize = 22.sp), cursorBrush = SolidColor(C.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) onSend(text.trim()) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                Box(Modifier.fillMaxWidth().height(2.dp).background(C.accent))
            }
            Spacer(Modifier.width(14.dp))
            val ready = text.isNotBlank()
            val a by animateFloatAsState(if (ready) 1f else 0.25f, tween(200), label = "sa")
            Box(Modifier.size(48.dp).graphicsLayer { alpha = a }.press(ready) { onSend(text.trim()) }.background(C.fg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, "Send", Modifier.size(22.dp), tint = C.bg)
            }
        }
        Text("cancel", style = T.mono, modifier = Modifier.padding(top = 14.dp).pressText(onCancel))
    }
}

@Composable
private fun Overview(st: AgentState) {
    val notes = st.notes.sortedByDescending { it.ts }.take(8)
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(bottom = 26.dp)) {
                BigStat("${st.beacons.count { it.present }}/${st.beacons.size}", "in range", Modifier.weight(1f))
                BigStat("${st.reminders.count { !it.fired }}", "pending", Modifier.weight(1f))
                BigStat("${st.convos.size}", "chats", Modifier.weight(1f))
            }
            Text("items", style = T.mono)
        }
        if (st.beacons.isEmpty()) item { Text("No beacons yet.", style = T.bodyDim, modifier = Modifier.padding(vertical = 16.dp)) }
        itemsIndexed(st.beacons) { _, b -> BeaconRow(b) }
        item { Spacer(Modifier.height(26.dp)); Text("activity", style = T.mono) }
        itemsIndexed(notes) { i, n ->
            Column {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    Text(ago(n.ts), style = T.mono, modifier = Modifier.width(72.dp).padding(top = 3.dp))
                    Text(n.text, style = T.body.copy(color = if (i == 0) C.fg else C.fg2), modifier = Modifier.weight(1f))
                }
                Hairline()
            }
        }
    }
}

@Composable
private fun BigStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = T.huge.copy(fontSize = 44.sp, lineHeight = 46.sp, fontFeatureSettings = "tnum"))
        Text(label, style = T.mono)
    }
}

@Composable
private fun PageHeader(title: String, sub: String, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("atlas", style = T.heading.copy(letterSpacing = 1.sp))
            Spacer(Modifier.weight(1f))
            Text("close", style = T.mono, modifier = Modifier.pressText(onClose).padding(vertical = 12.dp))
        }
        Spacer(Modifier.height(40.dp))
        Text(title, style = T.huge)
        Spacer(Modifier.height(4.dp))
        Text(sub, style = T.mono)
    }
}

@Composable
private fun RemindersPage(st: AgentState, onClose: () -> Unit) {
    val rows = st.reminders.sortedBy { it.fired }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp)) {
        PageHeader("Reminders", "${st.reminders.count { !it.fired }} pending", onClose)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (rows.isEmpty()) item { Text("Say “hey Atlas, remind me …”", style = T.bodyDim) }
            itemsIndexed(rows) { i, r ->
                Reveal(220 + i * 70) {
                    Column {
                        Hairline()
                        Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(r.message, style = T.heading.copy(fontSize = 21.sp, lineHeight = 26.sp, color = if (r.fired) C.fg3 else C.fg))
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    if (r.kind == "time") r.due?.let(::at) ?: "" else "when ${r.label} goes ${if (r.kind.endsWith("lost")) "out of range" else "back in range"}",
                                    style = T.mono,
                                )
                            }
                            Text(if (r.fired) "done" else "pending", style = T.mono.copy(color = if (r.fired) C.fg3 else C.accent), modifier = Modifier.padding(start = 12.dp, top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatsPage(st: AgentState, onClose: () -> Unit) {
    var open by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp)) {
        PageHeader("Chats", "${st.convos.size} remembered", onClose)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (st.convos.isEmpty()) item { Text("Talk near the wearable and Atlas keeps the thread.", style = T.bodyDim) }
            itemsIndexed(st.convos) { i, c ->
                val isOpen = open == c.id
                Reveal(220 + i * 70) {
                    Column(Modifier.pressText { open = if (isOpen) null else c.id }.animateContentSize(spring(stiffness = 260f))) {
                        Hairline()
                        Column(Modifier.fillMaxWidth().padding(vertical = 20.dp)) {
                            Text("${at(c.start)} · ${max(1, ((c.end - c.start) / 60).toInt())} min", style = T.mono)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                c.summary ?: c.lines.firstOrNull()?.let { "“$it”" } ?: "No transcript yet",
                                style = T.heading.copy(fontSize = 19.sp, lineHeight = 25.sp, color = if (c.summary == null) C.fg2 else C.fg), maxLines = if (isOpen) 30 else 3,
                            )
                            if (c.summary == null) Text("not summarized", style = T.mono, modifier = Modifier.padding(top = 6.dp))
                            SealLine(c, isOpen)
                            if (isOpen && c.lines.isNotEmpty()) {
                                Spacer(Modifier.height(14.dp))
                                c.lines.forEach { Text(it, style = T.small, modifier = Modifier.padding(bottom = 8.dp)) }
                            }
                            if (isOpen) ConvoActions(c)
                        }
                    }
                }
            }
            if (st.episodes.isNotEmpty()) {
                item { Spacer(Modifier.height(28.dp)); Hairline(); Text("Decisions · recorded on 0G", style = T.mono, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) }
                items(st.episodes) { e ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text("“${e.text.take(70)}”", style = T.small)
                        val (txt, col) = when {
                            e.status == "safe_stop" -> "safe-stopped · 0G unreachable" to C.warn
                            e.upload == "sealed" -> "✓ ${e.intent} · 0G Storage ${e.root?.take(8)}…${if (e.anchored) " · anchored on chain" else ""}" to C.ok
                            e.upload == "failed" -> "${e.intent} · upload retrying" to C.warn
                            else -> "${e.intent} · saving to 0G…" to C.accent
                        }
                        Text(txt, style = T.mono.copy(color = col), modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}


private val verdicts = mutableStateMapOf<Long, String>()

@Composable
private fun SealLine(c: Convo, open: Boolean) {
    val s = c.seal
    val v = verdicts[c.id]
    val (txt, col) = when {
        v != null -> v to (if (v.startsWith("✓")) C.ok else C.warn)
        s == null -> "not sealed" to C.fg3
        s.status == "uploading" -> "sealing on 0G…" to C.accent
        s.status == "sealed" -> "✓ sealed on 0G · ${s.root?.take(8)}…${s.root?.takeLast(4)}" to C.ok
        else -> "seal failed · tap save to retry" to C.warn
    }
    if (s != null || open) Text(txt, style = T.mono.copy(color = col), modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ConvoActions(c: Convo) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Text("share", style = T.mono.copy(color = C.fg), modifier = Modifier.pressText {
            scope.launch {
                val t = withContext(Dispatchers.IO) { Repo.exportText(ctx, c.id) } ?: return@launch
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, t) }
                ctx.startActivity(android.content.Intent.createChooser(send, "Share conversation").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        if (c.seal == null || c.seal.status == "failed") Text("save to 0G", style = T.mono.copy(color = C.fg), modifier = Modifier.pressText {
            scope.launch { verdicts.remove(c.id); withContext(Dispatchers.IO) { Repo.sealNow(ctx, c.id) }; Repo.fetch(ctx) }
        })
        if (c.seal?.status == "sealed") Text("verify", style = T.mono.copy(color = C.fg), modifier = Modifier.pressText {
            scope.launch {
                verdicts[c.id] = "checking 0G Storage…"
                val r = withContext(Dispatchers.IO) { Repo.verify(ctx, c.id) }
                verdicts[c.id] = when {
                    r == null -> "couldn't reach 0G Storage"
                    r.verified && !r.changed -> "✓ verified: matches the copy on 0G Storage"
                    r.verified -> "changed since it was sealed · save to 0G again"
                    else -> "✗ does not match 0G Storage"
                }
            }
        })
    }
}
