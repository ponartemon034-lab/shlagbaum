package com.example.shlagbaumrelay.data

import android.content.Context

/** Локальные настройки и учётные данные устройства. */
class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("shlagbaum", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = sp.getString("server", com.example.shlagbaumrelay.BuildConfig.DEFAULT_SERVER)!!
        set(v) = sp.edit().putString("server", v.trimEnd('/')).apply()

    var role: String?          // "controller" | "receiver" | null
        get() = sp.getString("role", null)
        set(v) = sp.edit().putString("role", v).apply()

    var deviceId: String?
        get() = sp.getString("deviceId", null)
        set(v) = sp.edit().putString("deviceId", v).apply()

    var authToken: String?
        get() = sp.getString("authToken", null)
        set(v) = sp.edit().putString("authToken", v).apply()

    /** Телефон B: разрешён ли реальный звонок (по умолчанию — безопасный тестовый режим). */
    var allowRealCall: Boolean
        get() = sp.getBoolean("allowRealCall", false)
        set(v) = sp.edit().putBoolean("allowRealCall", v).apply()

    /** Телефон B: включён ли резервный опрос очереди. */
    var pollEnabled: Boolean
        get() = sp.getBoolean("pollEnabled", false)
        set(v) = sp.edit().putBoolean("pollEnabled", v).apply()

    /** Индекс SIM-слота для звонка (0/1), -1 = по умолчанию. */
    var simSlot: Int
        get() = sp.getInt("simSlot", -1)
        set(v) = sp.edit().putInt("simSlot", v).apply()

    val isRegistered: Boolean get() = !deviceId.isNullOrBlank() && !authToken.isNullOrBlank()

    // --- Anti-replay: идентификаторы уже обработанных команд ---
    fun isProcessed(commandId: String): Boolean =
        sp.getStringSet("processed", emptySet())!!.contains(commandId)

    fun markProcessed(commandId: String) {
        val cur = HashSet(sp.getStringSet("processed", emptySet())!!)
        cur.add(commandId)
        // держим не более 200 последних
        val trimmed = if (cur.size > 200) cur.toList().takeLast(200).toSet() else cur
        sp.edit().putStringSet("processed", trimmed).apply()
    }
}
