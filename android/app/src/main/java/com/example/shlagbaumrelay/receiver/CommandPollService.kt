package com.example.shlagbaumrelay.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.shlagbaumrelay.call.OpenCallService
import com.example.shlagbaumrelay.data.Prefs
import com.example.shlagbaumrelay.net.ApiClient
import kotlin.concurrent.thread

/**
 * Телефон B. Резервная доставка команд ОПРОСОМ (pull): когда FCM не настроен или push не дошёл.
 * Держит лёгкий foreground-сервис и периодически опрашивает /pending. На каждую новую команду
 * запускает OpenCallService.
 *
 * Ограничение (честно): опрос работает, пока сервис жив. Это хорошо для локального теста и
 * короткой блокировки экрана, но НЕ гарантирует пробуждение из глубокого Doze при выключенном
 * экране на минуты и при выгрузке приложения из памяти. Надёжное пробуждение в этом случае даёт
 * высокоприоритетный FCM data-push (см. FcmService.kt.template и DEVICE_CALL_BACKGROUND.md).
 */
class CommandPollService : Service() {

    @Volatile private var running = false
    private val intervalMs = 5_000L

    companion object {
        private const val CH = "poll"
        private const val NID = 5590
        private const val TAG = "ShlagbaumPoll"

        fun start(ctx: Context) {
            val i = Intent(ctx, CommandPollService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CommandPollService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        startForegroundCompat()
        running = true
        thread(name = "poll-loop") { loop() }
        return START_STICKY
    }

    override fun onDestroy() { running = false; super.onDestroy() }

    private fun loop() {
        val prefs = Prefs(this)
        val api = ApiClient(prefs.serverUrl, prefs.authToken)
        while (running) {
            try {
                if (prefs.authToken.isNullOrBlank()) { Thread.sleep(intervalMs); continue }
                val pending = api.pending()
                for (c in pending) {
                    if (c.status == "DISPATCHED" && !prefs.isProcessed(c.id)) {
                        Log.i(TAG, "pull: новая команда ${c.id}")
                        OpenCallService.start(this, c.id, c.barRef, c.barLabel)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "опрос: ${e.message}")
            }
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
        }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CH, "Ожидание команд", NotificationManager.IMPORTANCE_MIN))
        }
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setContentTitle("Приёмник активен")
            .setContentText("Ожидание команд открытия")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        startForeground(NID, n)
    }
}
