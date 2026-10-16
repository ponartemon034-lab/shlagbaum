package com.example.shlagbaumrelay.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.shlagbaumrelay.data.Prefs
import com.example.shlagbaumrelay.databinding.ActivityMainBinding
import com.example.shlagbaumrelay.net.ApiClient
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        b.etServer.setText(prefs.serverUrl)
        refreshRole()

        b.btnRoleController.setOnClickListener { register("controller") }
        b.btnRoleReceiver.setOnClickListener { register("receiver") }
        b.btnOpenRole.setOnClickListener { openRole() }
    }

    private fun refreshRole() {
        val r = prefs.role
        b.tvRole.text = when (r) {
            "controller" -> "Роль: пульт (A)" + if (prefs.isRegistered) " · зарегистрирован" else ""
            "receiver" -> "Роль: приёмник (B)" + if (prefs.isRegistered) " · зарегистрирован" else ""
            else -> "Роль не выбрана"
        }
        b.btnOpenRole.isEnabled = prefs.isRegistered && r != null
    }

    private fun register(role: String) {
        val url = normalizeUrl(b.etServer.text.toString().trim())
        if (url.isBlank()) {
            status("Сначала укажите адрес вашего сервера, например http://IP-сервера:8080.\n" +
                   "Без запущенного сервера приложение работать не будет (см. руководство).")
            return
        }
        prefs.serverUrl = url
        b.etServer.setText(url)
        status("Регистрация ($role)…")
        thread {
            try {
                val api = ApiClient(url)
                val creds = api.register(role, android.os.Build.MODEL, null)
                prefs.role = creds.role
                prefs.deviceId = creds.deviceId
                prefs.authToken = creds.authToken
                runOnUiThread { status("Готово. Устройство зарегистрировано как $role."); refreshRole() }
            } catch (e: Exception) {
                runOnUiThread { status(friendlyError(url, e)) }
            }
        }
    }

    /** Добавляет http:// если пользователь ввёл адрес без схемы. */
    private fun normalizeUrl(s: String): String {
        if (s.isBlank()) return ""
        return if (s.startsWith("http://") || s.startsWith("https://")) s else "http://$s"
    }

    private fun friendlyError(url: String, e: Exception): String = when (e) {
        is java.net.SocketTimeoutException, is java.net.ConnectException, is java.net.UnknownHostException, is java.io.IOException ->
            "Не удалось подключиться к серверу по адресу $url.\n" +
            "Проверьте, что сервер запущен и адрес правильный, а телефон в интернете.\n" +
            "Важно: 10.0.2.2 работает только в эмуляторе Android, а не на реальном телефоне — " +
            "укажите публичный адрес вашего сервера."
        else -> "Ошибка регистрации: ${e.message}"
    }

    private fun openRole() {
        prefs.serverUrl = normalizeUrl(b.etServer.text.toString().trim())
        when (prefs.role) {
            "controller" -> startActivity(Intent(this, ControllerActivity::class.java))
            "receiver" -> startActivity(Intent(this, ReceiverActivity::class.java))
        }
    }

    private fun status(s: String) { b.tvStatus.text = s }
}
