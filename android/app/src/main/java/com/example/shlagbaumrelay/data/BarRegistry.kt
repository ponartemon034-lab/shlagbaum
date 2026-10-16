package com.example.shlagbaumrelay.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Локальный справочник шлагбаумов на телефоне B: barRef → (метка, номер, разрешён реальный звонок).
 * Номер хранится ТОЛЬКО здесь и на сервер не отправляется. Это же — allowlist: B звонит
 * только на номер из этого списка для пришедшего barRef.
 */
data class Bar(val ref: String, val label: String, val phone: String, val allowRealCall: Boolean)

class BarRegistry(ctx: Context) {
    private val sp = ctx.getSharedPreferences("shlagbaum_bars", Context.MODE_PRIVATE)

    fun all(): List<Bar> {
        val arr = JSONArray(sp.getString("bars", "[]"))
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Bar(o.getString("ref"), o.getString("label"), o.getString("phone"), o.optBoolean("allow", false))
        }
    }

    fun get(ref: String): Bar? = all().firstOrNull { it.ref == ref }

    fun upsert(bar: Bar) {
        val list = all().filter { it.ref != bar.ref }.toMutableList()
        list.add(bar)
        save(list)
    }

    fun remove(ref: String) = save(all().filter { it.ref != ref })

    private fun save(list: List<Bar>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("ref", it.ref); put("label", it.label); put("phone", it.phone); put("allow", it.allowRealCall)
            })
        }
        sp.edit().putString("bars", arr.toString()).apply()
    }
}
