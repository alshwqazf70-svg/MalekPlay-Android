package com.malekplay.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import android.util.Log

data class DiscoveredNsdService(
    val name: String,
    val host: String?,
    val port: Int?
)

class NsdDiscoveryManager(private val context: Context) {

    companion object {
        const val SERVICE_TYPE = "_malekplay._tcp."
        const val SERVICE_NAME = "MalekPlay_UltraShare"
        private const val TAG = "NsdDiscoveryManager"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    fun discoverServices(): Flow<DiscoveredNsdService> = callbackFlow {
        val multicastLock = wifiManager.createMulticastLock("malekplay_nsd_lock").apply {
            setReferenceCounted(false)
        }
        var lockAcquired = false
        var discoveryRequested = false

        try {
            multicastLock.acquire()
            lockAcquired = true

            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    Log.d(TAG, "بدأ البحث: $regType")
                }

                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceType != SERVICE_TYPE) return
                    runCatching {
                        nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                Log.w(TAG, "فشل استخراج العنوان: $errorCode")
                            }

                            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                                val host = serviceInfo.host?.hostAddress
                                if (!host.isNullOrBlank() && serviceInfo.port in 1..65535) {
                                    trySend(DiscoveredNsdService(serviceInfo.serviceName, host, serviceInfo.port))
                                }
                            }
                        })
                    }.onFailure { failure ->
                        Log.w(TAG, "تعذر بدء حل اسم خدمة NSD.", failure)
                    }
                }

                override fun onServiceLost(service: NsdServiceInfo) {
                    Log.d(TAG, "فُقدت خدمة: ${service.serviceName}")
                }

                override fun onDiscoveryStopped(serviceType: String) {
                    Log.d(TAG, "توقف البحث: $serviceType")
                }

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    close(IllegalStateException("فشل بدء اكتشاف الأجهزة عبر NSD: $errorCode"))
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "فشل إيقاف اكتشاف NSD: $errorCode")
                    close(IllegalStateException("فشل إيقاف اكتشاف الأجهزة عبر NSD: $errorCode"))
                }
            }

            // Mark before calling the framework: a synchronous exception still enters
            // finally and attempts a harmless best-effort stop for this listener.
            discoveryRequested = true
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

            awaitClose {
                if (discoveryRequested) {
                    runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                        .onFailure { Log.w(TAG, "تعذر إيقاف مستمع NSD.", it) }
                }
            }
        } finally {
            if (lockAcquired && multicastLock.isHeld) {
                runCatching { multicastLock.release() }
                    .onFailure { Log.w(TAG, "تعذر تحرير MulticastLock.", it) }
            }
        }
    }.distinctUntilChanged()
}
