package com.malekplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Owns the LocalOnlyHotspot reservation and credential lifecycle.
 * Platform callback work (credential and interface discovery, reservation close) is dispatched to IO.
 */
internal class UltraShareHotspotController(
    context: Context,
    private val scope: CoroutineScope
) {
    private val appContext = context.applicationContext
    @Volatile private var callback: WifiManager.LocalOnlyHotspotCallback? = null
    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile var isStarting: Boolean = false
        private set
    val isActive: Boolean get() = reservation != null

    @Suppress("MissingPermission")
    fun start(
        onStarted: (ssid: String, password: String, addresses: List<String>) -> Unit,
        onReservationStopped: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onFailure("إنشاء نقطة اتصال داخل MalekPlayer يتطلب Android 8 أو أحدث. استخدم نقطة اتصال النظام يدوياً.")
            return
        }
        if (reservation != null || isStarting) return
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            onFailed("خدمة Wi-Fi غير متاحة على هذا الجهاز.")
            return
        }
        isStarting = true
        val nextCallback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(nextReservation: WifiManager.LocalOnlyHotspotReservation) {
                val callbackInstance = this
                scope.launch(Dispatchers.IO) {
                    if (callback !== callbackInstance) {
                        runCatching { nextReservation.close() }
                        return@launch
                    }
                    reservation = nextReservation
                    isStarting = false
                    val credentials = runCatching {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val config = nextReservation.softApConfiguration
                            config.ssid.orEmpty() to (config.passphrase ?: "")
                        } else {
                            @Suppress("DEPRECATION")
                            val config = nextReservation.wifiConfiguration
                            config?.SSID?.trim('"').orEmpty() to config?.preSharedKey?.trim('"').orEmpty()
                        }
                    }.getOrDefault("" to "")
                    val addresses = UltraShareDeviceNetworkInfo.findLocalIpv4Addresses(appContext)
                    onStarted(credentials.first, credentials.second, addresses)
                }
            }

            override fun onStopped() {
                if (callback !== this) return
                callback = null
                reservation = null
                isStarting = false
                onReservationStopped()
            }

            override fun onFailed(reason: Int) {
                if (callback !== this) return
                callback = null
                reservation = null
                isStarting = false
                val message = when (reason) {
                    WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE ->
                        "تعذر إنشاء الشبكة لأن نقطة اتصال النظام أو وضع Wi-Fi الحالي غير متوافق. أوقف نقطة الاتصال الأخرى أو استخدم شبكة Wi-Fi مشتركة."
                    WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED ->
                        "النظام أو سياسة الجهاز تمنع إنشاء نقطة اتصال محلية."
                    WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL ->
                        "لا تتوفر قناة Wi-Fi لإنشاء نقطة الاتصال حالياً."
                    else -> "تعذر إنشاء نقطة اتصال MalekPlayer. جرّب إيقاف نقطة اتصال النظام ثم أعد المحاولة."
                }
                onFailed(message)
            }
        }
        callback = nextCallback
        try {
            wifiManager.startLocalOnlyHotspot(nextCallback, Handler(Looper.getMainLooper()))
        } catch (failure: SecurityException) {
            callback = null
            isStarting = false
            onFailed("يلزم منح إذن الأجهزة القريبة لإنشاء شبكة Wi-Fi محلية.")
        } catch (failure: Exception) {
            callback = null
            isStarting = false
            onFailed(failure.localizedMessage ?: "تعذر بدء نقطة الاتصال.")
        }
    }

    fun stop() {
        callback = null
        isStarting = false
        val current = reservation
        reservation = null
        if (current != null) scope.launch(Dispatchers.IO) {
            runCatching { current.close() }
        }
    }
}
