package dev.ethmumbai.roomagent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Words rise in one after another; the accent word keeps a slow highlight sweep. */
@Composable
private fun Words(line: String, base: Int, accent: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        line.split(" ").forEachIndexed { i, w ->
            Reveal(base + i * 90) { if (accent) ShimmerText(w, T.huge) else Text(w, style = T.huge) }
        }
    }
}

@Composable
fun Landing(onStart: () -> Unit) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(1600, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f))) }
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { delay(1800); while (true) { beat++; delay(4200) } }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Orb(0.3f, beat, Modifier.fillMaxWidth().aspectRatio(1f).offset(y = 40.dp).graphicsLayer {
            alpha = rise.value; scaleX = 0.55f + 0.45f * rise.value; scaleY = 0.55f + 0.45f * rise.value
        })
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 28.dp)) {
            Reveal(300) { Text("atlas", style = T.heading.copy(letterSpacing = 1.sp)) }
            Spacer(Modifier.weight(1f))
            Words("Your room,", 700)
            Words("remembered.", 900, accent = true)
            Spacer(Modifier.height(16.dp))
            Reveal(1300) { Text("It listens. It remembers. It reminds you.", style = T.bodyDim) }
            Spacer(Modifier.height(36.dp))
            Reveal(1500) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HoldToBegin(onStart)
                    Spacer(Modifier.width(18.dp))
                    Text("hold to begin", style = T.mono)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Press and hold: a ring draws around the button, and the interaction completes when it closes. */
@Composable
private fun HoldToBegin(onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var done by remember { mutableStateOf(false) }
    Box(
        Modifier.size(84.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch { p.animateTo(1f, tween(850, easing = LinearEasing)); if (!done) { done = true; onDone() } }
                tryAwaitRelease()
                if (!done) { job.cancel(); scope.launch { p.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) } }
            })
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().scale(1f - 0.06f * p.value)) {
            val sw = 2.dp.toPx(); val inset = sw
            drawCircle(C.borderStrong, size.minDimension / 2 - inset, style = Stroke(sw))
            drawArc(C.fg, -90f, 360f * p.value, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(sw, cap = StrokeCap.Round))
            drawCircle(C.fg.copy(alpha = 0.12f + 0.88f * p.value), size.minDimension * 0.28f * (0.6f + 0.4f * p.value))
        }
        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(22.dp), tint = if (p.value > 0.6f) C.bg else C.fg)
    }
}

/** Underline field: no box, just a line that brightens on focus. */
@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, placeholder: String = "") {
    var focused by remember { mutableStateOf(false) }
    val line by animateColorAsState(if (focused) C.accent else C.borderStrong, tween(200), label = "fl")
    Column(modifier) {
        Text(label, style = T.mono)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            if (value.isEmpty()) Text(placeholder, style = T.heading.copy(color = C.fg3, fontSize = 20.sp))
            BasicTextField(
                value, onChange, singleLine = true, textStyle = T.heading.copy(fontSize = 20.sp), cursorBrush = SolidColor(C.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        Box(Modifier.fillMaxWidth().height(if (focused) 2.dp else 1.dp).background(line))
    }
}

private enum class Probe { Busy, Ok, Fail }

@Composable
fun Setup(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf(Repo.baseUrl(ctx)) }
    var probe by remember { mutableStateOf(Probe.Busy) }
    var beaconsSeen by remember { mutableIntStateOf(0) }
    val st by Repo.state.collectAsState()

    fun hasNotif() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var notif by remember { mutableStateOf(hasNotif()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notif = it }

    // Test the address continuously: whenever it changes, after a short pause.
    LaunchedEffect(url) {
        probe = Probe.Busy; delay(650)
        Repo.setBaseUrl(ctx, url)
        val s = withContext(Dispatchers.IO) { Repo.fetch(ctx) }
        probe = if (s != null) Probe.Ok else Probe.Fail
        beaconsSeen = s?.beacons?.size ?: 0
    }
    LaunchedEffect(step) { if (step == 2) while (true) { withContext(Dispatchers.IO) { Repo.fetch(ctx) }; delay(2500) } }

    val ready = when (step) { 0 -> probe == Probe.Ok; else -> true }
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(probe) { if (probe == Probe.Ok) beat++ }

    Box(Modifier.fillMaxSize().background(C.bg)) {
        Orb(if (probe == Probe.Ok) 0.35f else 0.1f, beat, Modifier.size(190.dp).align(Alignment.TopEnd).offset(x = 28.dp, y = 30.dp), think = step == 0 && probe == Probe.Busy)
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 28.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 20.dp, end = 120.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) { i ->
                    val fill by animateFloatAsState(if (i <= step) 1f else 0f, tween(600, easing = FastOutSlowInEasing), label = "seg")
                    Box(Modifier.weight(1f).height(2.dp).background(C.border)) { Box(Modifier.fillMaxWidth(fill).fillMaxHeight().background(C.fg)) }
                }
            }
            Spacer(Modifier.height(70.dp))
            AnimatedContent(
                step, Modifier.weight(1f),
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(spring(stiffness = Spring.StiffnessLow)) { it / 4 * dir } + fadeIn(tween(300)))
                        .togetherWith(slideOutHorizontally(tween(220)) { -it / 5 * dir } + fadeOut(tween(160)))
                }, label = "step",
            ) { s ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Text("0${s + 1} / 03", style = T.mono)
                    Spacer(Modifier.height(10.dp))
                    when (s) {
                        0 -> {
                            Text("Find your\nroom.", style = T.huge)
                            Spacer(Modifier.height(36.dp))
                            Field(url, { url = it }, "address")
                            Spacer(Modifier.height(14.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("wifi", style = T.mono.copy(color = if (url.contains("10.42.0.1")) C.fg else C.fg3), modifier = Modifier.pressText { url = "http://10.42.0.1:8000" })
                                Text("/", style = T.mono)
                                Text("emulator", style = T.mono.copy(color = if (url.contains("10.0.2.2")) C.fg else C.fg3), modifier = Modifier.pressText { url = "http://10.0.2.2:8000" })
                            }
                            Spacer(Modifier.height(36.dp))
                            ProbeLine(probe, beaconsSeen)
                        }
                        1 -> {
                            Text("Allow\nalerts?", style = T.huge)
                            Spacer(Modifier.height(18.dp))
                            Text("Reminders and “you left something” arrive as notifications, even with the app closed.", style = T.bodyDim)
                            Spacer(Modifier.height(34.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StateDot(if (notif) C.ok else C.fg3, notif)
                                Text(if (notif) "On" else "Off", style = T.mono.copy(color = if (notif) C.ok else C.fg3))
                                Spacer(Modifier.width(20.dp))
                                if (!notif) Text("allow", style = T.heading.copy(color = C.accent), modifier = Modifier.pressText {
                                    if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS) else notif = true
                                })
                            }
                        }
                        else -> {
                            Text("Tag your\nthings.", style = T.huge)
                            Spacer(Modifier.height(18.dp))
                            Text("Put a beacon on your bag or keys. Atlas tells you when it leaves your range.", style = T.bodyDim)
                            Spacer(Modifier.height(30.dp))
                            if (st.beacons.isEmpty()) Text("scanning…", style = T.mono)
                            st.beacons.forEachIndexed { i, b -> Reveal(i * 80) { BeaconRow(b) } }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                if (step > 0) Text("back", style = T.mono, modifier = Modifier.pressText { step-- }.padding(end = 20.dp))
                if (step < 2) Text("skip", style = T.mono, modifier = Modifier.pressText { step++ })
                Spacer(Modifier.weight(1f))
                val a by animateFloatAsState(if (ready) 1f else 0.25f, tween(250), label = "go")
                Box(
                    Modifier.size(68.dp).alpha(a).background(C.fg, androidx.compose.foundation.shape.CircleShape)
                        .press(ready) { if (step < 2) step++ else onDone() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Continue", Modifier.size(26.dp), tint = C.bg) }
            }
        }
    }
}

fun Modifier.pressText(onClick: () -> Unit): Modifier = this.press(onClick = onClick)

@Composable
fun StateDot(color: Color, pulse: Boolean) { PulseDot(color, pulse, 6.dp) }

@Composable
private fun ProbeLine(p: Probe, beacons: Int) {
    val (color, text) = when (p) {
        Probe.Busy -> C.fg3 to "reaching room…"
        Probe.Ok -> C.ok to "connected · $beacons beacon${if (beacons == 1) "" else "s"}"
        Probe.Fail -> C.bad to "no answer · is the laptop on the same Wi-Fi?"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StateDot(color, p == Probe.Ok)
        Text(text, style = T.mono.copy(color = color))
    }
}

@Composable
fun BeaconRow(b: Beacon) {
    val tint = if (b.present) C.ok else C.warn
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.name, style = T.heading.copy(fontSize = 20.sp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StateDot(tint, b.present)
                    Text(if (b.present) "in range${b.distanceM?.let { " · ~${"%.1f".format(it)} m" } ?: ""}${b.rssi?.let { " · $it dBm" } ?: ""}" else "out of range${b.distanceM?.let { " · last ~${"%.1f".format(it)} m" } ?: ""}", style = T.mono.copy(color = tint))
                }
            }
            SignalBars(b.rssi, b.present)
        }
        Hairline()
    }
}
