package com.malekplay.network

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * Reference-counted ownership for Wi-Fi and CPU locks across simultaneous send/receive sessions.
 * All transitions are serialized; callers may safely begin/end independent peer transfers.
 */
internal class UltraShareTransferLocks(context: Context) {
    private val appContext = context.applicationContext
    private val guard = Any()
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sending = false
    private var receivingCount = 0

    fun beginTransfer(isReceiving: Boolean) {
        synchronized(guard) {
            if (isReceiving) receivingCount++ else sending = true
            runCatching {
                if (wifiLock?.isHeld != true) {
                    val manager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    wifiLock = manager.createWifiLock(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                        else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "MalekPlayer:UltraShare"
                    ).apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            }
            runCatching {
                if (wakeLock?.isHeld != true) {
                    val manager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MalekPlayer:UltraShare").apply {
                        setReferenceCounted(false)
                        acquire(TRANSFER_LOCK_TIMEOUT_MS)
                    }
                }
            }
        }
    }

    /** Returns true when all send and receive owners have released their locks. */
    fun endTransfer(isReceiving: Boolean): Boolean = synchronized(guard) {
        if (isReceiving) receivingCount = (receivingCount - 1).coerceAtLeast(0)
        else sending = false
        val idle = !sending && receivingCount == 0
        if (idle) releaseLocked()
        idle
    }

    fun release() = synchronized(guard) { releaseLocked() }

    private fun releaseLocked() {
        sending = false
        receivingCount = 0
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
    }

    private companion object {
        const val TRANSFER_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
    }
}
