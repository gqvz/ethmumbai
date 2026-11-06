package dev.ethmumbai.roomagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground service: polls the bridge and turns each new agent notification into a system notification. */
class PollService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("status", "Connection", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel("agent", "Atlas", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "status").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Atlas connected").setOngoing(true).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        scope.launch {
            var first = true
            while (true) {
                Repo.fetch(this@PollService)?.let { s -> announce(s, first); first = false }
                delay(800)
            }
        }
        return START_STICKY
    }

    private fun announce(s: AgentState, first: Boolean) {
        var last = Repo.lastNotified(this)
        val top = s.notes.maxOfOrNull { it.id } ?: return
        if (top < last) { last = 0; Repo.setLastNotified(this, 0) }     // the bridge's history was cleared and ids restarted
        val fresh = s.notes.filter { it.id > last }.sortedBy { it.id }
        if (fresh.isEmpty()) return
        Repo.setLastNotified(this, fresh.last().id)
        if (first && last < 0) return            // don't replay history on first install
        val nm = getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fresh.forEach {
            nm.notify(1000 + it.id.toInt(), Notification.Builder(this, "agent").setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(it.source.replaceFirstChar(Char::uppercase)).setContentText(it.text)
                .setStyle(Notification.BigTextStyle().bigText(it.text)).setContentIntent(open).setAutoCancel(true).build())
        }
    }

    override fun onDestroy() { scope.cancel() }

    companion object {
        fun start(c: Context) = c.startForegroundService(Intent(c, PollService::class.java))
    }
}
