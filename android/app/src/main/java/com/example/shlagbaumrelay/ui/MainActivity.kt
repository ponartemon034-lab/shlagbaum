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
        prefs.serverUrl = b.etServer.text.toString().trim()
        status("Регистрация ($role)…")
        thread {
            try {
                val api = ApiClient(prefs.serverUrl)
                val creds = api.register(role, android.os.Build.MODEL, null)
                prefs.role = creds.role
                prefs.deviceId = creds.deviceId
                prefs.authToken = creds.authToken
                runOnUiThread { status("Готово. Устройство зарегистрировано как $role."); refreshRole() }
            } catch (e: Exception) {
                runOnUiThread { status("Ошибка регистрации: ${e.message}") }
            }
        }
    }

    private fun openRole() {
        prefs.serverUrl = b.etServer.text.toString().trim()
        when (prefs.role) {
            "controller" -> startActivity(Intent(this, ControllerActivity::class.java))
            "receiver" -> startActivity(Intent(this, ReceiverActivity::class.java))
        }
    }

    private fun status(s: String) { b.tvStatus.text = s }
}
