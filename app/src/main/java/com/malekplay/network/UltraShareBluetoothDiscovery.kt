package com.malekplay.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.Closeable

/** Classic Bluetooth inquiry is a discovery hint only, never a file-transfer transport. */
object UltraShareBluetoothDiscovery {
    data class Peer(val name: String, val address: String, val bonded: Boolean, val deviceType: Int)

    internal fun mergePeers(existing: List<Peer>, incoming: Peer): List<Peer> {
        val address = incoming.address.trim().uppercase()
        if (address.isBlank()) return existing
        val normalized = incoming.copy(
            name = incoming.name.trim().ifBlank { "جهاز Bluetooth" },
            address = address
        )
        return (existing.filterNot { it.address.equals(address, true) } + normalized)
            .sortedWith(compareByDescending<Peer> { it.bonded }.thenBy { it.name.lowercase() })
    }

    class Session(
        context: Context,
        private val onPeers: (List<Peer>) -> Unit,
        private val onScanning: (Boolean) -> Unit,
        private val onError: (String) -> Unit
    ) : Closeable {
        private val appContext = context.applicationContext
        private val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        private var closed = false
        private var peers = emptyList<Peer>()

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = bluetoothDeviceExtra(intent) ?: return
                        runCatching { peerFrom(device) }.onSuccess { peer ->
                            peers = mergePeers(peers, peer)
                            onPeers(peers)
                        }.onFailure {
                            onError("تعذر قراءة بيانات جهاز Bluetooth؛ تحقق من إذن الأجهزة القريبة.")
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onScanning(false)
                    BluetoothAdapter.ACTION_STATE_CHANGED -> {
                        if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_OFF) {
                            onScanning(false)
                            onError("تم إيقاف Bluetooth أثناء البحث.")
                        }
                    }
                }
            }
        }

        init {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            }
            ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        }

        @SuppressLint("MissingPermission")
        fun start() {
            check(!closed) { "جلسة Bluetooth غير نشطة." }
            val current = adapter ?: run {
                onScanning(false)
                onError("هذا الجهاز لا يحتوي على محول Bluetooth.")
                return
            }
            try {
                if (!current.isEnabled) {
                    onScanning(false)
                    onError("فعّل Bluetooth من إعدادات الجهاز ثم أعد البحث.")
                    return
                }
                peers = emptyList()
                current.bondedDevices.orEmpty().forEach { device ->
                    runCatching { peerFrom(device) }.getOrNull()?.let { peers = mergePeers(peers, it) }
                }
                onPeers(peers)
                current.cancelDiscovery()
                val started = current.startDiscovery()
                onScanning(started)
                if (!started) onError("تعذر بدء اكتشاف Bluetooth. أغلق البحث في التطبيقات الأخرى ثم حاول مجدداً.")
            } catch (_: SecurityException) {
                onScanning(false)
                onError("يلزم منح إذن الأجهزة القريبة لاكتشاف Bluetooth.")
            } catch (failure: Exception) {
                onScanning(false)
                onError(failure.localizedMessage ?: "تعذر بدء اكتشاف Bluetooth.")
            }
        }

        @SuppressLint("MissingPermission")
        fun stop() {
            if (!closed) runCatching { adapter?.cancelDiscovery() }
            onScanning(false)
        }

        @SuppressLint("MissingPermission")
        private fun peerFrom(device: BluetoothDevice) = Peer(
            name = device.name?.take(64)?.ifBlank { "جهاز Bluetooth" } ?: "جهاز Bluetooth",
            address = device.address.orEmpty(),
            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
            deviceType = device.type
        )

        @Suppress("DEPRECATION")
        private fun bluetoothDeviceExtra(intent: Intent): BluetoothDevice? =
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

        @SuppressLint("MissingPermission")
        override fun close() {
            if (closed) return
            closed = true
            runCatching { adapter?.cancelDiscovery() }
            runCatching { appContext.unregisterReceiver(receiver) }
            onScanning(false)
        }
    }

    fun open(context: Context, onPeers: (List<Peer>) -> Unit, onScanning: (Boolean) -> Unit, onError: (String) -> Unit) =
        Session(context, onPeers, onScanning, onError)
}
