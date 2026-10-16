package com.example.shlagbaumrelay.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contracts.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.shlagbaumrelay.call.CallPlacer
import com.example.shlagbaumrelay.data.Bar
import com.example.shlagbaumrelay.data.BarRegistry
import com.example.shlagbaumrelay.data.Prefs
import com.example.shlagbaumrelay.databinding.ActivityReceiverBinding
import com.example.shlagbaumrelay.net.ApiClient
import com.example.shlagbaumrelay.receiver.CommandPollService
import kotlin.concurrent.thread

/** Телефон B: справочник шлагбаумов (allowlist), выдача доступа кодом, режим приёма, диагностика. */
class ReceiverActivity : AppCompatActivity() {
    private lateinit var b: ActivityReceiverBinding
    private lateinit var prefs: Prefs
    private lateinit var bars: BarRegistry
    private val api by lazy { ApiClient(prefs.serverUrl, prefs.authToken) }

    private val reqPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val ok = res.values.all { it }
        status(if (ok) "Разрешения выданы" else "Часть разрешений не выдана — реальный звонок может не работать")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReceiverBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this); bars = BarRegistry(this)

        b.swRealCall.isChecked = prefs.allowRealCall
        b.swPoll.isChecked = prefs.pollEnabled
        renderBars()

        b.btnAddBar.setOnClickListener { addBar() }
        b.btnInvite.setOnClickListener { invite() }
        b.btnDiag.setOnClickListener { diag() }

        b.swRealCall.setOnCheckedChangeListener { _, on ->
            prefs.allowRealCall = on
            if (on) {
                requestCallPerms()
                status("ВНИМАНИЕ: реальные звонки разрешены. Используйте только тестовый номер.")
            } else status("Безопасный тестовый режим (звонки имитируются)")
        }
        b.swPoll.setOnCheckedChangeListener { _, on ->
            prefs.pollEnabled = on
            if (on) { ensureNotifPerm(); askIgnoreBattery(); CommandPollService.start(this); status("Опрос очереди включён") }
            else { CommandPollService.stop(this); status("Опрос очереди выключен") }
        }
    }

    private fun addBar() {
        val label = b.etBarLabel.text.toString().trim()
        val ref = b.etBarRef.text.toString().trim()
        val phone = b.etBarPhone.text.toString().trim()
        if (label.isBlank() || ref.isBlank() || phone.isBlank()) { status("Заполните название, метку и номер"); return }
        bars.upsert(Bar(ref, label, phone, b.cbAllowReal.isChecked))
        renderBars(); status("Шлагбаум сохранён (номер только на этом телефоне)")
    }

    private fun renderBars() {
        val list = bars.all()
        b.tvBars.text = if (list.isEmpty()) "Список шлагбаумов пуст"
            else list.joinToString("\n") { x ->
                "• ${x.label} [${x.ref}] ${maskPhone(x.phone)}${if (x.allowRealCall) " (звонок разрешён)" else ""}"
            }
    }

    private fun invite() {
        val label = b.etBarLabel.text.toString().trim()
        val ref = b.etBarRef.text.toString().trim()
        if (label.isBlank() || ref.isBlank()) { status("Введите название и метку шлагбаума для приглашения"); return }
        status("Создание кода…")
        thread {
            try { val code = api.createInvite(label, ref); runOnUiThread { b.tvInvite.text = "Код: $code"; status("Передайте код владельцу пульта (действует 15 минут)") } }
            catch (e: Exception) { runOnUiThread { status("Ошибка: ${e.message}") } }
        }
    }

    private fun diag() {
        status("Проверка связи…")
        thread {
            val sb = StringBuilder()
            try { sb.append("Сервер: ").append(api.health()).append('\n') } catch (e: Exception) { sb.append("Сервер недоступен: ${e.message}\n") }
            sb.append("Разрешения на звонок: ").append(if (CallPlacer.hasCallPermissions(this)) "есть" else "нет").append('\n')
            sb.append("Телефония: ").append(if (CallPlacer.hasTelephony(this)) "есть" else "нет").append('\n')
            sb.append("Режим звонка: ").append(if (prefs.allowRealCall) "реальный" else "тестовый (имитация)")
            runOnUiThread { status(sb.toString()) }
        }
    }

    private fun requestCallPerms() {
        reqPerms.launch(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE))
    }
    private fun ensureNotifPerm() {
        if (Build.VERSION.SDK_INT >= 33) reqPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    private fun askIgnoreBattery() {
        // Явно и с пояснением: исключение из оптимизации батареи нужно, чтобы приёмник не убивался.
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
        catch (_: Exception) {}
    }

    private fun maskPhone(p: String) = if (p.length <= 4) p else p.take(2) + "***" + p.takeLast(2)
    private fun status(s: String) { b.tvStatus.text = s }
}
