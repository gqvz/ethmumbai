package dev.ethmumbai.roomagent

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

/** Records 16 kHz mono PCM16 (what the bridge's 0G speech-to-text expects) until [stop]. [level] is the live loudness, 0..1. */
class Recorder {
    @Volatile var level = 0f
    @Volatile private var running = false
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private val out = ByteArrayOutputStream()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192))
        } catch (e: Exception) { return false }
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
        out.reset(); rec = r; running = true; r.startRecording()
        thread = Thread {
            val b = ShortArray(1600)
            while (running) {
                val n = r.read(b, 0, b.size)
                if (n <= 0) continue
                var sum = 0.0
                val bytes = ByteArray(n * 2)
                for (i in 0 until n) { sum += b[i] * b[i].toDouble(); bytes[2 * i] = (b[i].toInt() and 0xFF).toByte(); bytes[2 * i + 1] = (b[i].toInt() shr 8).toByte() }
                synchronized(out) { out.write(bytes) }
                level = (sqrt(sum / n) / 6000.0).toFloat().coerceIn(0f, 1f)
            }
        }.also { it.start() }
        return true
    }

    /** Audio captured since the last call. */
    fun drain(): ByteArray = synchronized(out) { val b = out.toByteArray(); out.reset(); b }

    fun stop(): ByteArray {
        running = false; thread?.join(500); level = 0f
        rec?.let { try { it.stop() } catch (_: Exception) {}; it.release() }; rec = null
        return drain()
    }
}
