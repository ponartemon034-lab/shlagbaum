package com.example.shlagbaumrelay.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.shlagbaumrelay.data.BarRegistry
import com.example.shlagbaumrelay.data.Prefs
import com.example.shlagbaumrelay.net.ApiClient
import kotlin.concurrent.thread

/**
 * Телефон B. Foreground-сервис типа phoneCall. Обрабатывает одну доставленную команду:
 * проверяет anti-replay и allowlist, проводит статусы на сервере и инициирует вызов.
 * Запускается из FcmService (push) или CommandPollService (pull) — в т.ч. при заблокированном экране.
 */
class OpenCallService : Service() {

    companion object {
        const val EXTRA_COMMAND_ID = "commandId"
        const val EXTRA_BAR_REF = "barRef"
        const val EXTRA_BAR_LABEL = "barLabel"
        private const val CH = "open_call"
        private const val NID = 5588
        private const val TAG = "ShlagbaumCall"

        fun start(ctx: Context, commandId: String, barRef: String, barLabel: String) {
            val i = Intent(ctx, OpenCallService::class.java)
                .putExtra(EXTRA_COMMAND_ID, commandId)
                .putExtra(EXTRA_BAR_REF, barRef)
                .putExtra(EXTRA_BAR_LABEL, barLabel)
            ContextCompat_startForegroundService(ctx, i)
        }

        private fun ContextCompat_startForegroundService(ctx: Context, i: Intent) {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        val commandId = intent?.getStringExtra(EXTRA_COMMAND_ID)
        val barRef = intent?.getStringExtra(EXTRA_BAR_REF).orEmpty()
        val barLabel = intent?.getStringExtra(EXTRA_BAR_LABEL).orEmpty()
        if (commandId.isNullOrBlank()) { stopSelfCompat(); return START_NOT_STICKY }
        thread(name = "open-call") { handle(commandId, barRef, barLabel) }
        return START_NOT_STICKY
    }

    private fun handle(commandId: String, barRef: String, barLabel: String) {
        val prefs = Prefs(this)
        val api = ApiClient(prefs.serverUrl, prefs.authToken)
        try {
            // 1) Anti-replay: повторная доставка той же команды не инициирует второй звонок.
            if (prefs.isProcessed(commandId)) {
                Log.i(TAG, "команда $commandId уже обработана — пропуск")
                return
            }
            // 2) Подтверждаем получение. Сервер проверит срок/отзыв и допустимость перехода.
            try { api.ack(commandId, "RECEIVED") }
            catch (e: Exception) { Log.w(TAG, "ack RECEIVED отклонён: ${e.message}"); return }

            // 3) Номер берём ЛОКАЛЬНО по barRef (в команде номера нет). Это же — allowlist.
            val bar = BarRegistry(this).get(barRef)
            if (bar == null) {
                safeAck(api, commandId, "CALL_BLOCKED", "bar_not_in_allowlist")
                return
            }
            // 4) Готовность телефонии/разрешений (только если реальный звонок разрешён).
            if (prefs.allowRealCall && !CallPlacer.hasCallPermissions(this)) {
                safeAck(api, commandId, "CALL_BLOCKED", "no_permission")
                return
            }

            // 5) Запрашиваем вызов и инициируем.
            safeAck(api, commandId, "CALL_REQUESTED", null)
            val res = CallPlacer.placeCall(this, bar.phone, prefs.simSlot, prefs.allowRealCall && bar.allowRealCall)
            prefs.markProcessed(commandId)

            when (res) {
                CallPlacer.Result.PLACED, CallPlacer.Result.SIMULATED ->
                    safeAck(api, commandId, "COMPLETED", if (res == CallPlacer.Result.SIMULATED) "simulated" else null)
                CallPlacer.Result.NO_PERMISSION -> safeAck(api, commandId, "CALL_BLOCKED", "no_permission")
                CallPlacer.Result.NO_TELEPHONY -> safeAck(api, commandId, "CALL_BLOCKED", "no_telephony")
                CallPlacer.Result.ERROR -> safeAck(api, commandId, "ERROR", "place_call_failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "обработка команды упала", e)
            runCatching { api.ack(commandId, "ERROR", "exception") }
        } finally {
            stopSelfCompat()
        }
    }

    private fun safeAck(api: ApiClient, id: String, status: String, err: String?) {
        runCatching { api.ack(id, status, err) }.onFailure { Log.w(TAG, "ack $status: ${it.message}") }
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CH, "Открытие шлагбаума", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, CH)
            .setContentTitle("Выполняется команда открытия")
            .setContentText("Инициирование вызова на номер шлагбаума")
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        } else {
            startForeground(NID, n)
        }
    }

    private fun stopSelfCompat() {
        if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }
}
