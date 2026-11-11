package com.malekplay.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import java.io.Closeable
import java.security.SecureRandom
import java.util.UUID

/**
 * BLE discovery hint for nearby MalekPlay peers.
 * Only a short-lived random token and service UUID are broadcast. Pairing secrets,
 * file metadata, and transfer endpoints never appear in BLE advertisements.
 * BLE is discovery-only; authenticated pairing and file transfer use Wi-Fi.
 */
object UltraShareBleDiscovery {
    private val SERVICE_UUID = ParcelUuid(UUID.fromString("b9e4d9b0-2f0a-4d37-9d22-6d616c656b70"))

    data class Peer(val name: String, val address: String, val deviceType: Int)

    internal fun mergePeers(existing: List<Peer>, incoming: Peer): List<Peer> {
        val address = incoming.address.trim().uppercase()
        if (address.isBlank()) return existing
        val normalized = incoming.copy(
            name = incoming.name.trim().ifBlank { "جهاز MalekPlay عبر BLE" },
            address = address
        )
        return (existing.filterNot { it.address.equals(address, true) } + normalized)
            .sortedBy { it.name.lowercase() }
    }

    class Session(
        context: Context,
        private val onPeers: (List<Peer>) -> Unit,
        private val onScanning: (Boolean) -> Unit,
        private val onError: (String) -> Unit
    ) : Closeable {
        private val appContext = context.applicationContext
        private val adapter: BluetoothAdapter? =
            (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        private val serviceData = ByteArray(8).also(SecureRandom()::nextBytes)
        private var scanner: BluetoothLeScanner? = null
        private var advertiser: BluetoothLeAdvertiser? = null
        private var closed = false
        private var scanning = false
        private var advertising = false
        private var peers = emptyList<Peer>()

        private val scanCallback = object : ScanCallback() {
            @SuppressLint("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (closed) return
                val record = result.scanRecord ?: return
                val token = record.getServiceData(SERVICE_UUID) ?: return
                if (token.size != 8) return
                val device = result.device
                val peer = runCatching {
                    Peer(
                        name = record.deviceName?.take(64)?.ifBlank { null }
                            ?: device.name?.take(64)?.ifBlank { null }
                            ?: "جهاز MalekPlay عبر BLE",
                        address = device.address.orEmpty(),
                        deviceType = device.type
                    )
                }.getOrNull() ?: return
                peers = mergePeers(peers, peer)
                onPeers(peers)
            }

            override fun onScanFailed(errorCode: Int) {
                scanning = false
                onScanning(false)
                onError("فشل مسح BLE (رمز $errorCode). تحقق من تفعيل Bluetooth ثم أعد المحاولة.")
            }
        }

        private val advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                advertising = true
            }

            override fun onStartFailure(errorCode: Int) {
                advertising = false
                onError("تعذر إعلان وجود MalekPlay عبر BLE (رمز $errorCode)؛ سيستمر البحث عن الأجهزة.")
            }
        }

        @SuppressLint("MissingPermission")
        fun start() {
            check(!closed) { "جلسة BLE غير نشطة." }
            val current = adapter ?: run {
                onScanning(false)
                onError("هذا الجهاز لا يحتوي على Bluetooth.")
                return
            }
            try {
                if (!current.isEnabled) {
                    onScanning(false)
                    onError("فعّل Bluetooth ثم أعد اكتشاف أجهزة BLE.")
                    return
                }
                val currentScanner = current.bluetoothLeScanner ?: run {
                    onScanning(false)
                    onError("ماسح BLE غير متاح على هذا الجهاز.")
                    return
                }
                scanner = currentScanner
                advertiser = current.bluetoothLeAdvertiser
                peers = emptyList()
                onPeers(emptyList())
                val filter = ScanFilter.Builder().setServiceData(SERVICE_UUID, byteArrayOf()).build()
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                currentScanner.startScan(listOf(filter), settings, scanCallback)
                scanning = true
                onScanning(true)

                advertiser?.let { activeAdvertiser ->
                    val advertiseSettings = AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                        .setConnectable(false)
                        .setTimeout(0)
                        .build()
                    val data = AdvertiseData.Builder()
                        // A 128-bit service-data AD structure plus the flags fits the
                        // legacy 31-byte advertisement limit; adding a separate 128-bit
                        // UUID list would exceed it on many phones.
                        .addServiceData(SERVICE_UUID, serviceData)
                        .setIncludeDeviceName(false)
                        .build()
                    runCatching {
                        activeAdvertiser.startAdvertising(advertiseSettings, data, advertiseCallback)
                    }.onFailure {
                        onError("تعذر بدء إعلان BLE؛ سيستمر مسح الأجهزة القريبة.")
                    }
                } ?: onError("هذا الجهاز يدعم مسح BLE لكنه لا يدعم الإعلان عن نفسه.")
            } catch (failure: SecurityException) {
                stop()
                onError("يلزم منح أذونات Bluetooth Scan وConnect وAdvertise لاستخدام BLE.")
            } catch (failure: Exception) {
                stop()
                onError(failure.localizedMessage ?: "تعذر بدء اكتشاف BLE.")
            }
        }

        @SuppressLint("MissingPermission")
        fun stop() {
            if (closed) return
            runCatching { if (scanning) scanner?.stopScan(scanCallback) }
            runCatching { if (advertising) advertiser?.stopAdvertising(advertiseCallback) }
            scanning = false
            advertising = false
            onScanning(false)
        }

        override fun close() {
            if (closed) return
            stop()
            closed = true
            scanner = null
            advertiser = null
        }
    }

    fun open(
        context: Context,
        onPeers: (List<Peer>) -> Unit,
        onScanning: (Boolean) -> Unit,
        onError: (String) -> Unit
    ): Session = Session(context, onPeers, onScanning, onError)
}
