package com.malekplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android-runtime smoke tests for APIs that JVM protocol tests cannot exercise.
 * These tests use loopback only; they do not replace two-physical-device acceptance tests.
 */
@RunWith(AndroidJUnit4::class)
class UltraShareNetworkRuntimeInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun activeAndroidNetworkCanBindSocketAndCarryDuplexPayloads() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network: Network? = manager.activeNetwork
        Assume.assumeTrue("The test device has no active Android Network", network != null)

        val outbound = ByteArray(256 * 1024) { index -> (index * 31).toByte() }
        val inbound = ByteArray(192 * 1024) { index -> (index * 17 + 3).toByte() }

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 8_000
            val client = Socket()
            network!!.bindSocket(client)
            client.soTimeout = 8_000
            client.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.localPort), 5_000)
            server.accept().use { accepted ->
                accepted.soTimeout = 8_000
                client.use { connected ->
                    val writer = Thread {
                        connected.getOutputStream().write(outbound)
                        connected.getOutputStream().flush()
                    }
                    val peerWriter = Thread {
                        accepted.getOutputStream().write(inbound)
                        accepted.getOutputStream().flush()
                    }
                    writer.start()
                    peerWriter.start()

                    val receivedByPeer = ByteArray(outbound.size).also { DataInputStream(accepted.getInputStream()).readFully(it) }
                    val receivedByClient = ByteArray(inbound.size).also { DataInputStream(connected.getInputStream()).readFully(it) }
                    writer.join(8_000)
                    peerWriter.join(8_000)

                    assertTrue("Local sender thread did not finish", !writer.isAlive)
                    assertTrue("Local receiver thread did not finish", !peerWriter.isAlive)
                    assertArrayEquals(outbound, receivedByPeer)
                    assertArrayEquals(inbound, receivedByClient)
                }
            }
        }
    }

    @Test
    fun connectivityManagerCanRequestWifiRouteWhenDeviceProvidesOne() {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val available = CountDownLatch(1)
        var selected: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                selected = network
                available.countDown()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            manager.requestNetwork(request, callback, 8_000)
            if (!available.await(9, TimeUnit.SECONDS)) {
                Assume.assumeTrue("No Wi-Fi transport is available on this emulator/device", false)
            }
            assertNotNull("Android reported Wi-Fi available without a Network", selected)
        } finally {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    @Test
    fun interruptedSocketCanResumeFromDurablePartialFileAfterReconnect() {
        val firstPart = ByteArray(48 * 1024) { index -> (index * 13).toByte() }
        val secondPart = ByteArray(32 * 1024) { index -> (index * 7 + 5).toByte() }
        val expected = firstPart + secondPart
        val partial = File(context.cacheDir, "ultrashare-resume-runtime-${System.nanoTime()}.part")

        try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                server.soTimeout = 8_000
                Socket().use { firstClient ->
                    firstClient.connect(InetSocketAddress("127.0.0.1", server.localPort), 5_000)
                    firstClient.getOutputStream().write(firstPart)
                    firstClient.getOutputStream().flush()
                    firstClient.shutdownOutput()
                    server.accept().use { accepted ->
                        val received = ByteArray(firstPart.size)
                        DataInputStream(accepted.getInputStream()).readFully(received)
                        FileOutputStream(partial, false).use { output ->
                            output.write(received)
                            output.fd.sync()
                        }
                    }
                }

                assertTrue(partial.length() == firstPart.size.toLong())
                assertTrue(
                    UltraShareBatchTransport.resumableFileOffset(partial, expected.size.toLong()) ==
                        firstPart.size.toLong()
                )

                Socket().use { resumedClient ->
                    resumedClient.connect(InetSocketAddress("127.0.0.1", server.localPort), 5_000)
                    resumedClient.getOutputStream().write(secondPart)
                    resumedClient.getOutputStream().flush()
                    resumedClient.shutdownOutput()
                    server.accept().use { accepted ->
                        val resumed = ByteArray(secondPart.size)
                        DataInputStream(accepted.getInputStream()).readFully(resumed)
                        FileOutputStream(partial, true).use { output ->
                            output.write(resumed)
                            output.fd.sync()
                        }
                    }
                }
            }

            assertArrayEquals(expected, partial.readBytes())
        } finally {
            partial.delete()
        }
    }
}
