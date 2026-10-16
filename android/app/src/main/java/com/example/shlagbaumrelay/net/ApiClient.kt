package com.example.shlagbaumrelay.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Данные устройства (сохраняются локально). */
data class DeviceCreds(val deviceId: String, val authToken: String, val role: String)

/** Команда открытия (как её видит клиент; nonce сервер наружу не отдаёт). */
data class Command(
    val id: String,
    val pairingId: String,
    val barRef: String,
    val barLabel: String,
    val status: String,
    val errorCode: String?,
    val expiresAt: Long,
)

data class Pairing(
    val id: String,
    val barLabel: String,
    val barRef: String,
    val controllerId: String,
    val receiverId: String,
    val revoked: Boolean,
)

class ApiException(val code: Int, msg: String) : Exception(msg)

/**
 * Тонкий синхронный клиент backend. Вызывать из фонового потока (Dispatchers.IO).
 * Все запросы идут по HTTPS в бою; в прототипе допускается http для локального сервера.
 */
class ApiClient(private var baseUrl: String, private var authToken: String? = null) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    fun setAuth(token: String?) { authToken = token }
    fun setBaseUrl(url: String) { baseUrl = url.trimEnd('/') }

    private fun req(path: String, method: String, body: JSONObject?): JSONObject {
        val b = Request.Builder().url(baseUrl.trimEnd('/') + path)
        authToken?.let { b.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> b.get()
            "POST" -> b.post((body ?: JSONObject()).toString().toRequestBody(JSON))
            else -> throw IllegalArgumentException(method)
        }
        http.newCall(b.build()).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(txt).optString("error") }.getOrNull() ?: txt
                throw ApiException(resp.code, msg.ifBlank { "HTTP ${resp.code}" })
            }
            return if (txt.isBlank()) JSONObject() else JSONObject(txt)
        }
    }

    fun health(): JSONObject = req("/v1/health", "GET", null)

    fun register(role: String, label: String?, fcmToken: String?): DeviceCreds {
        val o = req("/v1/devices/register", "POST", JSONObject().apply {
            put("role", role); put("label", label); put("fcmToken", fcmToken)
        })
        return DeviceCreds(o.getString("deviceId"), o.getString("authToken"), o.getString("role"))
    }

    fun updateFcmToken(fcmToken: String) {
        req("/v1/devices/token", "POST", JSONObject().put("fcmToken", fcmToken))
    }

    /** B: создать код приглашения. */
    fun createInvite(barLabel: String, barRef: String): String =
        req("/v1/pairings/invite", "POST", JSONObject().apply { put("barLabel", barLabel); put("barRef", barRef) })
            .getString("code")

    /** A: погасить код → привязка. */
    fun redeemInvite(code: String): Pairing {
        val o = req("/v1/pairings/redeem", "POST", JSONObject().put("code", code))
        return Pairing(o.getString("pairingId"), o.getString("barLabel"), o.getString("barRef"), "", o.optString("receiverId"), false)
    }

    fun listPairings(): List<Pairing> {
        val arr = req("/v1/pairings", "GET", null).optJSONArray("pairings") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Pairing(
                p.getString("id"), p.getString("bar_label"), p.getString("bar_ref"),
                p.optString("controller_id"), p.optString("receiver_id"),
                !p.isNull("revoked_at"),
            )
        }
    }

    fun revokePairing(id: String) { req("/v1/pairings/$id/revoke", "POST", null) }

    /** A: отправить команду «открыть». commandId генерирует клиент — для идемпотентности при повторной отправке. */
    fun sendCommand(pairingId: String, commandId: String): Command =
        parseCommand(req("/v1/commands", "POST", JSONObject().apply {
            put("pairingId", pairingId); put("commandId", commandId)
        }).getJSONObject("command"))

    fun getCommand(id: String): Command = parseCommand(req("/v1/commands/$id", "GET", null).getJSONObject("command"))

    /** B: забрать незавершённые команды. */
    fun pending(): List<Command> {
        val arr = req("/v1/commands/pending", "GET", null).optJSONArray("commands") ?: JSONArray()
        return (0 until arr.length()).map { parseCommand(arr.getJSONObject(it)) }
    }

    /** B: сообщить стадию. */
    fun ack(id: String, status: String, errorCode: String? = null): Command =
        parseCommand(req("/v1/commands/$id/ack", "POST", JSONObject().apply {
            put("status", status); if (errorCode != null) put("errorCode", errorCode)
        }).getJSONObject("command"))

    private fun parseCommand(o: JSONObject) = Command(
        id = o.getString("id"),
        pairingId = o.getString("pairing_id"),
        barRef = o.getString("bar_ref"),
        barLabel = o.getString("bar_label"),
        status = o.getString("status"),
        errorCode = if (o.isNull("error_code")) null else o.optString("error_code"),
        expiresAt = o.optLong("expires_at"),
    )
}
