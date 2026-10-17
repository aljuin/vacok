package com.messenger.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Сессия входа, которую страница передаёт приложению (нужна службе уведомлений) */
object Session {
    private const val PREFS = "vacok_session"

    data class Cfg(val server: String, val token: String, val uid: String)

    fun save(ctx: Context, server: String, token: String, uid: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server", server)
            .putString("token", token)
            .putString("uid", uid)
            .apply()
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun load(ctx: Context): Cfg? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val s = p.getString("server", null)
        val t = p.getString("token", null)
        val u = p.getString("uid", null)
        if (s.isNullOrEmpty() || t.isNullOrEmpty() || u.isNullOrEmpty()) return null
        return Cfg(s, t, u)
    }
}

/**
 * Держит соединение с сервером в фоне (даже когда приложение закрыто)
 * и показывает системные уведомления о новых сообщениях.
 */
class SyncService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_SYNC, "Фоновая связь", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_MSG, "Сообщения", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cfg = Session.load(this)
        if (cfg == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (!running) {
            running = true
            worker = Thread(Runnable { loop(cfg) }, "vacok-sync").apply {
                isDaemon = true
                start()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        super.onDestroy()
    }

    private fun goForeground() {
        val n = NotificationCompat.Builder(this, CH_SYNC)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle("vacok")
            .setContentText("Сообщения приходят в фоне")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(SYNC_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SYNC_ID, n)
        }
    }

    private fun loop(cfg: Session.Cfg) {
        var delay = 2_000L
        while (running) {
            try {
                listen(cfg)
                delay = 2_000L
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                // нет связи — попробуем снова через паузу
            }
            if (!running) break
            try {
                Thread.sleep(delay)
            } catch (e: InterruptedException) {
                break
            }
            delay = minOf(delay * 2, 60_000L)
        }
    }

    private fun listen(cfg: Session.Cfg) {
        val conn = URL(cfg.server + "/api/stream").openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Authorization", "Bearer " + cfg.token)
            conn.setRequestProperty("Accept", "text/event-stream")
            conn.connectTimeout = 15_000
            conn.readTimeout = 75_000   // сервер присылает ping каждые 20 секунд
            conn.useCaches = false
            val code = conn.responseCode
            if (code == 401) {
                // вход больше не действует — выходим из фоновой службы
                Session.clear(this)
                running = false
                stopSelf()
                return
            }
            if (code != 200) throw IllegalStateException("HTTP $code")
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var event = ""
                while (running) {
                    val line = reader.readLine() ?: break
                    when {
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            if (event == "message") onMessage(cfg, line.substring(5).trim())
                            event = ""
                        }
                        line.isEmpty() -> event = ""
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun onMessage(cfg: Session.Cfg, data: String) {
        val m = JSONObject(data)
        val to = m.optString("to")
        val from = m.optString("from")
        if (to != cfg.uid || from == cfg.uid) return          // только входящие
        if (MainActivity.inForeground) return                  // приложение открыто — покажет чат
        val name = m.optJSONObject("peer")?.optString("name").orEmpty().ifEmpty { "Новое сообщение" }
        val body = when (m.optString("kind")) {
            "img" -> "📷 Фото"
            "vid" -> if (m.optJSONObject("media")?.optInt("circ") == 1) "🎥 Видеокружок" else "🎥 Видео"
            "voice" -> "🎤 Голосовое"
            else -> m.optString("text").ifEmpty { "Сообщение" }
        }
        showMessage(name, body, from)
    }

    @SuppressLint("MissingPermission")
    private fun showMessage(title: String, body: String, from: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("chatId", from)
        }
        val pi = PendingIntent.getActivity(
            this, from.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CH_MSG)
            .setSmallIcon(R.drawable.ic_stat_message)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .build()
        NotificationManagerCompat.from(this).notify(from.hashCode(), n)
    }

    companion object {
        private const val CH_SYNC = "sync"
        private const val CH_MSG = "messages"
        private const val SYNC_ID = 1001
    }
}
