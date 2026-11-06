package dev.ethmumbai.roomagent

import android.graphics.Color as AColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT))
        PollService.start(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = C.accent, background = C.bg, surface = C.surface, onSurface = C.fg, onBackground = C.fg)) {
                Surface(Modifier.fillMaxSize(), color = C.bg) { App() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val ctx = LocalContext.current
    var stage by remember { mutableIntStateOf(if (Repo.onboarded(ctx)) 2 else 0) }
    var settings by remember { mutableStateOf(false) }

    AnimatedContent(
        stage, Modifier.fillMaxSize().background(C.bg),
        transitionSpec = { (fadeIn(tween(420, 120)) + scaleIn(tween(420, 120), 0.98f)).togetherWith(fadeOut(tween(220))) }, label = "stage",
    ) { s ->
        when (s) {
            0 -> Landing { stage = 1 }
            1 -> Setup { Repo.setOnboarded(ctx, true); stage = 2 }
            else -> Main { settings = true }
        }
    }
    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = C.surface, contentColor = C.fg) {
        SettingsSheet(onClose = { settings = false }, onReplay = { Repo.setOnboarded(ctx, false); settings = false; stage = 0 })
    }
}

@Composable
private fun SettingsSheet(onClose: () -> Unit, onReplay: () -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(Repo.baseUrl(ctx)) }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
        Text("Settings", style = T.title)
        Spacer(Modifier.height(20.dp))
        Field(url, { url = it }, "address", placeholder = "http://10.42.0.1:8000")
        Spacer(Modifier.height(10.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("tailscale", style = T.mono.copy(color = if (url.contains("100.74.67.52")) C.fg else C.fg3), modifier = Modifier.pressText { url = "http://100.74.67.52:8000" })
            Text("Wi-Fi", style = T.mono.copy(color = if (url.contains("10.42.0.1")) C.fg else C.fg3), modifier = Modifier.pressText { url = "http://10.42.0.1:8000" })
            Text("emulator", style = T.mono.copy(color = if (url.contains("10.0.2.2")) C.fg else C.fg3), modifier = Modifier.pressText { url = "http://10.0.2.2:8000" })
        }
        Spacer(Modifier.height(22.dp))
        RoomButton("Save", Modifier.fillMaxWidth()) { Repo.setBaseUrl(ctx, url); onClose() }
        Spacer(Modifier.height(8.dp))
        var sure by remember { mutableStateOf(false) }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        RoomButton(if (sure) "Tap again to delete everything" else "Delete all conversations, activity and reminders", Modifier.fillMaxWidth(), Btn.Ghost) {
            if (!sure) sure = true else scope.launch { withContext(Dispatchers.IO) { Repo.clearAll(ctx) }; sure = false; onClose() }
        }
        Spacer(Modifier.height(8.dp))
        RoomButton("Replay welcome", Modifier.fillMaxWidth(), Btn.Ghost, onClick = onReplay)
    }
}
