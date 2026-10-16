package com.example.shlagbaumrelay.ui

import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.example.shlagbaumrelay.data.Prefs
import com.example.shlagbaumrelay.databinding.ActivityControllerBinding
import com.example.shlagbaumrelay.net.ApiClient
import com.example.shlagbaumrelay.net.Command
import com.example.shlagbaumrelay.net.Pairing
import java.util.UUID
import kotlin.concurrent.thread

/** Телефон A: добавление по коду, список шлагбаумов, кнопка «Открыть» и наблюдение за статусом. */
class ControllerActivity : AppCompatActivity() {
    private lateinit var b: ActivityControllerBinding
    private lateinit var prefs: Prefs
    private val api by lazy { ApiClient(prefs.serverUrl, prefs.authToken) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityControllerBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.btnRedeem.setOnClickListener { redeem(b.etCode.text.toString().trim()) }
        b.btnRefresh.setOnClickListener { loadPairings() }
        loadPairings()
    }

    private fun redeem(code: String) {
        if (code.isBlank()) { status("Введите код"); return }
        status("Добавление по коду…")
        thread {
            try { val p = api.redeemInvite(code); runOnUiThread { status("Добавлен: ${p.barLabel}"); loadPairings() } }
            catch (e: Exception) { runOnUiThread { status("Ошибка: ${e.message}") } }
        }
    }

    private fun loadPairings() {
        thread {
            try {
                val list = api.listPairings().filter { !it.revoked }
                runOnUiThread { renderPairings(list) }
            } catch (e: Exception) { runOnUiThread { status("Ошибка списка: ${e.message}") } }
        }
    }

    private fun renderPairings(list: List<Pairing>) {
        b.llPairings.removeAllViews()
        if (list.isEmpty()) { status("Нет добавленных шлагбаумов"); return }
        for (p in list) {
            val btn = Button(this).apply {
                text = "Открыть: ${p.barLabel}"
                setOnClickListener { sendOpen(p) }
            }
            b.llPairings.addView(btn)
        }
    }

    private fun sendOpen(p: Pairing) {
        // Клиентский commandId → защита от дублей при повторной отправке.
        val commandId = UUID.randomUUID().toString()
        status("Отправка команды…")
        thread {
            try {
                val cmd = api.sendCommand(p.id, commandId)
                runOnUiThread { status(humanStatus(cmd)) }
                watch(commandId)
            } catch (e: Exception) { runOnUiThread { status("Ошибка отправки: ${e.message}") } }
        }
    }

    /** Опрос статуса команды до терминального состояния. */
    private fun watch(commandId: String) {
        thread {
            val terminal = setOf("COMPLETED", "EXPIRED", "REVOKED", "DUPLICATE_IGNORED", "NOT_ALLOWED", "DEVICE_OFFLINE", "CALL_BLOCKED", "ERROR")
            repeat(20) {
                try {
                    val c = api.getCommand(commandId)
                    runOnUiThread { status(humanStatus(c)) }
                    if (c.status in terminal) return@thread
                } catch (_: Exception) {}
                Thread.sleep(1500)
            }
        }
    }

    private fun humanStatus(c: Command): String = "Статус: " + when (c.status) {
        "DISPATCHED" -> "команда отправлена телефону B"
        "RECEIVED" -> "телефон B получил команду"
        "CALL_REQUESTED" -> "запрошен вызов"
        "COMPLETED" -> if (c.errorCode == "simulated") "вызов сымитирован (тестовый режим)" else "вызов инициирован"
        "CALL_BLOCKED" -> "Android/настройки не дали позвонить (${c.errorCode ?: "?"})"
        "DEVICE_OFFLINE" -> "телефон B недоступен"
        "EXPIRED" -> "команда просрочена"
        "REVOKED" -> "доступ отозван"
        "ERROR" -> "ошибка (${c.errorCode ?: "?"})"
        else -> c.status
    } + "\n(Это статус вызова, а не подтверждение открытия шлагбаума.)"

    private fun status(s: String) { b.tvStatus.text = s }
}
