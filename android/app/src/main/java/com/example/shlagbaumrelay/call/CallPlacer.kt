package com.example.shlagbaumrelay.call

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Инициирование исходящего вызова. Тот же приём, что в оригинале (проверен в бою):
 * TelecomManager.placeCall(tel:номер) с выбором SIM через PHONE_ACCOUNT_HANDLE.
 * Работает из фонового foreground-сервиса типа phoneCall, в т.ч. при заблокированном экране,
 * БЕЗ роли дозвонщика по умолчанию и БЕЗ Accessibility.
 *
 * ВАЖНО: это НЕ подтверждает открытие шлагбаума — только что вызов инициирован.
 */
object CallPlacer {
    private const val TAG = "ShlagbaumCall"

    enum class Result { PLACED, SIMULATED, NO_PERMISSION, NO_TELEPHONY, ERROR }

    fun hasCallPermissions(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /**
     * @param allowRealCall если false — безопасный тестовый режим: реальный звонок НЕ выполняется.
     */
    @SuppressLint("MissingPermission")
    fun placeCall(ctx: Context, phone: String, simSlot: Int, allowRealCall: Boolean): Result {
        if (!allowRealCall) {
            Log.i(TAG, "SAFE TEST MODE: звонок НЕ выполнен (симуляция) на $phone")
            return Result.SIMULATED
        }
        if (!hasCallPermissions(ctx)) return Result.NO_PERMISSION
        val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return Result.NO_TELEPHONY
        return try {
            val uri = Uri.fromParts("tel", phone, null)
            val extras = Bundle().apply {
                putBoolean(TelecomManager.EXTRA_START_CALL_WITH_SPEAKERPHONE, false)
                phoneAccountForSlot(ctx, simSlot)?.let { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            }
            if (tm.isInCall) { Log.w(TAG, "уже идёт вызов"); return Result.ERROR }
            tm.placeCall(uri, extras)
            Log.i(TAG, "placeCall выполнен на $phone")
            Result.PLACED
        } catch (e: SecurityException) {
            Log.e(TAG, "нет прав на звонок", e); Result.NO_PERMISSION
        } catch (e: Exception) {
            Log.e(TAG, "ошибка звонка", e); Result.ERROR
        }
    }

    /** Выбор PhoneAccountHandle по индексу SIM-слота (как в оригинале). -1 = по умолчанию. */
    @SuppressLint("MissingPermission")
    private fun phoneAccountForSlot(ctx: Context, slot: Int): PhoneAccountHandle? {
        if (slot < 0) return null
        return try {
            val tm = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val accounts = tm.callCapablePhoneAccounts
            if (accounts.isNullOrEmpty()) return null
            val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val sub = sm?.activeSubscriptionInfoList?.firstOrNull { it.simSlotIndex == slot }
            // по возможности сопоставляем по subscriptionId, иначе берём по порядку
            sub?.let { s ->
                accounts.firstOrNull { it.id.startsWith(s.subscriptionId.toString()) }
            } ?: accounts.getOrNull(slot)
        } catch (e: Exception) {
            Log.w(TAG, "SIM slot select failed", e); null
        }
    }

    fun hasTelephony(ctx: Context): Boolean =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) ||
        (Build.VERSION.SDK_INT >= 23)
}
