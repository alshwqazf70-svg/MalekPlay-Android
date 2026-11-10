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
            setReferenceCounted(true)
            acquire()
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d(TAG, "بدأ البحث: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == SERVICE_TYPE) {
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Log.e(TAG, "فشل استخراج العنوان: $errorCode")
                        }

                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress
                            trySend(DiscoveredNsdService(serviceInfo.serviceName, host, serviceInfo.port))
                        }
                    })
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
        }

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            if (multicastLock.isHeld) multicastLock.release()
        }
    }.distinctUntilChanged()
}
