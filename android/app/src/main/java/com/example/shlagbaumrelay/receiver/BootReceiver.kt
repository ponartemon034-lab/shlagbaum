package com.example.shlagbaumrelay.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.shlagbaumrelay.data.Prefs

/** Перезапуск опроса очереди после перезагрузки (если приёмник был включён). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (prefs.role == "receiver" && prefs.pollEnabled) {
            CommandPollService.start(context)
        }
    }
}
