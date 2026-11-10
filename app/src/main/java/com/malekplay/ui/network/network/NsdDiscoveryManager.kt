package com.malekplay.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class DiscoveredNsdService(
    val name: String,
    val host: String?,
    val port: Int?
)

class NsdDiscoveryManager(private val context: Context) {

    companion object {
        const val SERVICE_TYPE = "_malekplay._tcp."
        const val SERVICE_NAME = "MalekPlay_UltraShare"
    }

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * يبدأ البحث عن خدمات MalekPlay القريبة ويعيد تدفقاً (Flow) بالخدمات المكتشفة.
     * يتطلب هذا التحسين الحصول على MulticastLock لضمان استقبال حزم mDNS.
     */
    fun discoverServices(): Flow<DiscoveredNsdService> = callbackFlow {
        val multicastLock = wifiManager.createMulticastLock("malekplay_nsd_lock").apply {
            setReferenceCounted(true)
            acquire()
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) { /* يمكن تسجيل الحالة هنا */ }
            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType == SERVICE_TYPE) {
                    // عند العثور على خدمة، نحتاج إلى استخراج عنوان IP والمنفذ
                    nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { /* تجاهل */ }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress
                            trySend(DiscoveredNsdService(serviceInfo.serviceName, host, serviceInfo.port))
                        }
                    })
                }
            }
            override fun onServiceLost(service: NsdServiceInfo) { /* يمكن إزالة الخدمة من القائمة */ }
            override fun onDiscoveryStopped(serviceType: String) { /* إيقاف */ }
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
