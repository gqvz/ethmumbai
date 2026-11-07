package dev.ethmumbai.roomagent

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Beacon(val name: String, val present: Boolean, val rssi: Int?, val distanceM: Double? = null, val source: String? = null)
data class Note(val id: Long, val ts: Double, val text: String, val source: String)
data class Reminder(val id: Long, val kind: String, val due: Double?, val label: String?, val message: String, val fired: Boolean)
data class Seal(val sha256: String, val root: String?, val tx: String?, val status: String)
data class Verdict(val verified: Boolean, val changed: Boolean, val error: String?)
data class Convo(val id: Long, val start: Double, val end: Double, val summary: String?, val lines: List<String>, val seal: Seal? = null)
data class Episode(val id: Long, val ts: Double, val text: String, val intent: String, val status: String, val root: String?, val anchored: Boolean, val upload: String)
data class Live(val text: String, val ts: Double)
data class AgentState(
    val beacons: List<Beacon> = emptyList(),
    val notes: List<Note> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val convos: List<Convo> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val session: Boolean = false,
    val live: Live? = null,
)

/** Talks to the laptop bridge (backend/bridge.py): GET /api/state, POST /api/command. */
object Repo {
    private val _state = MutableStateFlow(AgentState())
    val state: StateFlow<AgentState> = _state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    @Volatile private var fails = 0

    private fun prefs(c: Context) = c.getSharedPreferences("agent", Context.MODE_PRIVATE)

    fun defaultUrl() = if (Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"))
        "http://10.0.2.2:8000" else "http://100.74.67.52:8000"

    fun baseUrl(c: Context): String = prefs(c).getString("url", null) ?: defaultUrl()
    fun setBaseUrl(c: Context, url: String) { prefs(c).edit().putString("url", url.trim().trimEnd('/')).apply() }

    fun onboarded(c: Context): Boolean = prefs(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context, v: Boolean) { prefs(c).edit().putBoolean("onboarded", v).apply() }

    /** Highest notification id already announced, so each one raises a system notification once. */
    fun lastNotified(c: Context): Long = prefs(c).getLong("last_note", -1)
    fun setLastNotified(c: Context, id: Long) { prefs(c).edit().putLong("last_note", id).apply() }

    /** Prefer the Wi-Fi network over mobile data, even if Android considers it unvalidated. */
    private fun open(c: Context, url: String): HttpURLConnection {
        val u = URL(url)
        if (!u.host.startsWith("127.")) {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            if (wifi != null) return wifi.openConnection(u) as HttpURLConnection
        }
        return u.openConnection() as HttpURLConnection
    }

    fun fetch(c: Context): AgentState? = try {
        val conn = open(c, baseUrl(c) + "/api/state")
        conn.connectTimeout = 6000; conn.readTimeout = 8000
        val s = parse(JSONObject(conn.inputStream.bufferedReader().readText()))
        _state.value = s; _error.value = null; fails = 0; s
    } catch (e: Exception) {
        android.util.Log.w("Repo", "fetch ${baseUrl(c)} failed: $e")
        if (++fails >= 3) _error.value = "Can't reach ${baseUrl(c)}"      // ride out brief network hiccups instead of flashing "Reconnecting"
        null
    }

    fun sendCommand(c: Context, text: String): Boolean = try {
        val conn = open(c, baseUrl(c) + "/api/command")
        conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 2500; conn.readTimeout = 4000
        conn.outputStream.use { it.write(JSONObject().put("text", text).toString().toByteArray()) }
        conn.responseCode in 200..299
    } catch (e: Exception) { false }

    /** Sends recorded 16 kHz PCM16 to the bridge, which transcribes it with 0G and runs it as a command. Returns the transcript, or null if it could not be sent. */
    fun sendVoice(c: Context, pcm: ByteArray): String? = try {
        val conn = open(c, baseUrl(c) + "/api/voice")
        conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 3000; conn.readTimeout = 60000
        conn.setFixedLengthStreamingMode(pcm.size)
        conn.outputStream.use { it.write(pcm) }
        val j = JSONObject(conn.inputStream.bufferedReader().readText())
        if (j.optBoolean("ok", true)) j.optString("text") else null
    } catch (e: Exception) { android.util.Log.w("Repo", "voice failed: $e"); null }

    private fun post(c: Context, path: String, body: ByteArray, readMs: Int): JSONObject {
        val conn = open(c, baseUrl(c) + path)
        conn.requestMethod = "POST"; conn.doOutput = true; conn.connectTimeout = 3000; conn.readTimeout = readMs
        conn.setFixedLengthStreamingMode(body.size)
        conn.outputStream.use { it.write(body) }
        return JSONObject(conn.inputStream.bufferedReader().readText())
    }

    /** Streams recorded audio while speaking; returns the transcript so far (the bridge transcribes with 0G as it arrives). */
    fun voiceChunk(c: Context, sid: String, pcm: ByteArray): String? = try { post(c, "/api/voice/chunk?sid=$sid", pcm, 8000).optString("text") } catch (e: Exception) { null }

    /** Ends the stream; the bridge returns the final transcript and runs it as a command. Null if it could not be sent. */
    fun voiceEnd(c: Context, sid: String, pcm: ByteArray): String? = try {
        val j = post(c, "/api/voice/end?sid=$sid", pcm, 60000)
        if (j.optBoolean("ok", true)) j.optString("text") else null
    } catch (e: Exception) { android.util.Log.w("Repo", "voice end failed: $e"); null }

    fun clearAll(c: Context): Boolean = try { post(c, "/api/clear", ByteArray(0), 8000).optBoolean("ok"); fetch(c); true } catch (e: Exception) { false }

    private fun get(c: Context, path: String, readMs: Int = 10000): String {
        val conn = open(c, baseUrl(c) + path); conn.connectTimeout = 6000; conn.readTimeout = readMs
        return conn.inputStream.bufferedReader().readText()
    }

    /** Markdown with summary, transcript and 0G proof, for the share sheet. */
    fun exportText(c: Context, id: Long): String? = try { get(c, "/export?conv=$id") } catch (e: Exception) { null }

    /** Checks the conversation against the copy on 0G Storage. */
    fun verify(c: Context, id: Long): Verdict? = try {
        val j = JSONObject(get(c, "/api/verify?conv=$id", 60000))
        Verdict(j.optBoolean("verified"), j.optBoolean("changed"), if (j.has("remote_error")) j.optString("remote_error") else null)
    } catch (e: Exception) { null }

    fun sealNow(c: Context, id: Long): Boolean = try { post(c, "/api/seal?conv=$id", ByteArray(0), 8000); true } catch (e: Exception) { false }

    private fun parse(j: JSONObject): AgentState {
        val bj = j.getJSONObject("beacons")
        val beacons = bj.keys().asSequence().map { k ->
            val b = bj.getJSONObject(k)
            Beacon(k, b.optBoolean("present"), if (b.isNull("rssi")) null else b.optInt("rssi"),
                if (b.isNull("distance_m")) null else b.optDouble("distance_m"), if (b.isNull("source")) null else b.optString("source"))
        }.toList()
        fun <T> arr(key: String, f: (JSONObject) -> T): List<T> {
            val a = j.getJSONArray(key); return (0 until a.length()).map { f(a.getJSONObject(it)) }
        }
        val speech = arr("transcripts") { it }.filter { it.optString("kind") == "speech" }
        return AgentState(
            beacons = beacons,
            notes = arr("notifications") { Note(it.getLong("id"), it.getDouble("ts"), it.getString("text"), it.optString("source")) },
            reminders = arr("reminders") {
                Reminder(it.getLong("id"), it.getString("kind"), if (it.isNull("due")) null else it.getDouble("due"),
                    if (it.isNull("label")) null else it.getString("label"), it.getString("message"), it.optInt("fired") == 1)
            },
            convos = arr("convos") { c ->
                val id = c.getLong("id")
                Convo(id, c.getDouble("start"), c.getDouble("end"), if (c.isNull("summary")) null else c.getString("summary"),
                    speech.filter { it.optLong("conv") == id }.map { it.getString("text") }.reversed(),
                    c.optJSONObject("seal")?.let { Seal(it.getString("sha256"), it.optString("root").ifBlank { null }, it.optString("tx").ifBlank { null }, it.getString("status")) })
            },
            episodes = j.optJSONArray("episodes")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { e ->
                Episode(e.getLong("id"), e.getDouble("ts"), e.optString("text"), e.optString("intent"), e.optString("status"), e.optString("root").ifBlank { null }, e.optString("anchor_tx").isNotBlank() && !e.isNull("anchor_tx"), e.optString("upload")) } } } ?: emptyList(),
            session = j.optBoolean("session"),
            live = j.optJSONObject("live")?.let { Live(it.getString("text"), it.getDouble("ts")) },
        )
    }
}
