package com.malekplay.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WpsInfo
import android.os.Looper
import androidx.core.content.ContextCompat
import java.io.Closeable

/**
 * Lifecycle-bound Wi-Fi Direct discovery and connection helper.
 * Pairing secrets are deliberately not part of discovery or connection messages.
 */
object UltraShareWifiDirect {
    data class Peer(
        val name: String,
        val deviceAddress: String,
        val status: String
    )

    class Session internal constructor(
        context: Context,
        private val manager: WifiP2pManager,
        private val channel: WifiP2pManager.Channel,
        private val onPeers: (List<Peer>) -> Unit,
        private val onConnected: (host: String?, isGroupOwner: Boolean) -> Unit,
        private val onError: (String) -> Unit
    ) : Closeable {
        private val appContext = context.applicationContext
        private var closed = false

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(
                            WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED
                        )
                        if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                            onError("فعّل Wi-Fi وميزة Wi-Fi Direct من إعدادات الجهاز.")
                        }
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                }
            }
        }

        init {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        fun discover() {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            try {
                manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = Unit
                    override fun onFailure(reason: Int) {
                        onError("تعذر البحث عبر Wi-Fi Direct (رمز الخطأ $reason).")
                    }
                })
            } catch (failure: SecurityException) {
                onError("يلزم منح إذن الأجهزة القريبة للبحث عبر Wi-Fi Direct.")
            }
        }

        fun connect(deviceAddress: String, preferThisDeviceAsGroupOwner: Boolean) {
            check(!closed) { "جلسة Wi-Fi Direct غير نشطة." }
            val config = WifiP2pConfig().apply {
                this.deviceAddress = deviceAddress
                // A receiving phone should own the group; a sending phone prefers
                // the remote receiver so groupOwnerAddress identifies its TCP server.
                groupOwnerIntent = if (preferThisDeviceAsGroupOwner) 15 else 0
                wps.setup = WpsInfo.PBC
            }
            try {
                manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        // Connection details arrive through WIFI_P2P_CONNECTION_CHANGED_ACTION.
                    }
                    override fun onFailure(reason: Int) {
                        onError("تعذر الاتصال بجهاز Wi-Fi Direct (رمز الخطأ $reason).")
                    }
                })
            } catch (failure: SecurityException) {
                onError("يلزم إذن الأجهزة القريبة للاتصال عبر Wi-Fi Direct.")
            }
        }

        private fun requestPeers() {
            try {
                manager.requestPeers(channel) { list ->
                    if (closed) return@requestPeers
                    onPeers(list.deviceList.map { device ->
                        Peer(
                            name = device.deviceName?.takeIf { it.isNotBlank() } ?: "جهاز Wi-Fi Direct",
                            deviceAddress = device.deviceAddress.orEmpty(),
                            status = statusLabel(device.status)
                        )
                    }.filter { it.deviceAddress.isNotBlank() })
                }
            } catch (failure: SecurityException) {
                onError("يلزم إذن الأجهزة القريبة لعرض أجهزة Wi-Fi Direct.")
            }
        }

        private fun requestConnectionInfo() {
            try {
                manager.requestConnectionInfo(channel) { info ->
                    if (closed) return@requestConnectionInfo
                    if (!info.groupFormed) {
                        onError("انقطع اتصال Wi-Fi Direct.")
                        return@requestConnectionInfo
                    }
                    if (info.isGroupOwner) {
                        onConnected(null, true)
                    } else {
                        val host = info.groupOwnerAddress?.hostAddress
                        if (host != null && UltraShareTransport.isValidPrivateLanHost(host)) {
                            onConnected(host, false)
                        } else {
                            onError("تم اتصال Wi-Fi Direct لكن تعذر تحديد عنوان جهاز الاستقبال.")
                        }
                    }
                }
            } catch (failure: SecurityException) {
                onError("يلزم إذن الأجهزة القريبة لقراءة اتصال Wi-Fi Direct.")
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            runCatching { appContext.unregisterReceiver(receiver) }
        }

        private fun statusLabel(status: Int): String = when (status) {
            WifiP2pDevice.AVAILABLE -> "متاح"
            WifiP2pDevice.INVITED -> "تمت دعوته"
            WifiP2pDevice.CONNECTED -> "متصل"
            WifiP2pDevice.FAILED -> "فشل الاتصال"
            WifiP2pDevice.UNAVAILABLE -> "غير متاح"
            else -> "غير معروف"
        }
    }

    fun open(
        context: Context,
        onPeers: (List<Peer>) -> Unit,
        onConnected: (host: String?, isGroupOwner: Boolean) -> Unit,
        onError: (String) -> Unit
    ): Session {
        val manager = context.applicationContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: error("هذا الجهاز لا يدعم Wi-Fi Direct.")
        val channel = manager.initialize(context.applicationContext, Looper.getMainLooper(), null)
            ?: error("تعذر تهيئة Wi-Fi Direct على هذا الجهاز.")
        return Session(context, manager, channel, onPeers, onConnected, onError)
    }
}
