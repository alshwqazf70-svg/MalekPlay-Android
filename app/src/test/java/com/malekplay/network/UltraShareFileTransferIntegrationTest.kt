package com.malekplay.network

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

/**
 * End-to-end sender/receiver test using the production TCP and AES-GCM transport.
 * Robolectric supplies Android's ContentResolver; the payload itself is a real file.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UltraShareFileTransferIntegrationTest {

    @Test
    fun sendsAndReceivesRealFileAndVerifiesItsContents() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val host = findPrivateIpv4Address()
        val pin = "004219837"
        val payload = ByteArray(128 * 1024 + 37) { index -> ((index * 31) xor (index ushr 3)).toByte() }
        val sourceDirectory = java.nio.file.Files.createTempDirectory("ultrashare-source").toFile()
        val destinationDirectory = java.nio.file.Files.createTempDirectory("ultrashare-destination").toFile()
        val source = File(sourceDirectory, "اختبار-نقل.bin").apply { writeBytes(payload) }
        val port = ServerSocket(0).use { it.localPort }
        var receivedProgress = 0L
        var sentProgress = 0L

        val receiver = async {
            UltraShareTransport.receiveOnce(
                port = port,
                pairingSecret = pin,
                destinationDirectory = destinationDirectory,
                onProgress = { receivedProgress = it.transferredBytes }
            )
        }

        try {
            // Allow the receiver to bind before the sender opens its TCP connection.
            delay(400)
            UltraShareTransport.send(
                resolver = context.contentResolver,
                uri = Uri.fromFile(source),
                host = host,
                port = port,
                pairingSecret = pin,
                fileName = source.name,
                mimeType = "application/octet-stream",
                sizeBytes = payload.size.toLong(),
                onProgress = { sentProgress = it.transferredBytes }
            )

            val received = withTimeout(30_000) { receiver.await() }
            assertTrue("The received file must exist", received.file.isFile)
            assertEquals(source.name, received.fileName)
            assertEquals(payload.size.toLong(), received.sizeBytes)
            assertEquals(payload.size.toLong(), receivedProgress)
            assertEquals(payload.size.toLong(), sentProgress)
            assertArrayEquals("The received bytes must exactly match the source", payload, received.file.readBytes())
            assertTrue(
                "The completed transfer receipt should be persisted for safe duplicate retries",
                destinationDirectory.listFiles().orEmpty().any { it.name.endsWith(".received") }
            )
        } finally {
            receiver.cancelAndJoin()
            sourceDirectory.deleteRecursively()
            destinationDirectory.deleteRecursively()
        }
    }

    @Test
    fun resumesFromPersistedPartialFileAfterTcpDisconnect() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val host = findPrivateIpv4Address()
        val pin = "004219837"
        val payload = ByteArray(UltraShareTransport.CHUNK_BYTES * 3 + 113) { index ->
            ((index * 17) xor (index ushr 5)).toByte()
        }
        val sourceDirectory = java.nio.file.Files.createTempDirectory("ultrashare-resume-source").toFile()
        val destinationDirectory = java.nio.file.Files.createTempDirectory("ultrashare-resume-destination").toFile()
        val source = File(sourceDirectory, "resume-test.bin").apply { writeBytes(payload) }
        val port = ServerSocket(0).use { it.localPort }
        try {
            val firstReceiver = async {
                runCatching {
                    UltraShareTransport.receiveOnce(port, pin, destinationDirectory)
                }
            }
            delay(400)
            val interrupted = runCatching {
                UltraShareTransport.send(
                    resolver = context.contentResolver, uri = Uri.fromFile(source), host = host,
                    port = port, pairingSecret = pin, fileName = source.name,
                    mimeType = "application/octet-stream", sizeBytes = payload.size.toLong(),
                    onProgress = { progress ->
                        if (progress.transferredBytes >= UltraShareTransport.CHUNK_BYTES) {
                            throw java.io.IOException("Simulated TCP interruption after first chunk")
                        }
                    }
                )
            }
            assertTrue("The first transfer must be interrupted", interrupted.isFailure)
            assertTrue("The receiver must observe the dropped TCP stream", firstReceiver.await().isFailure)
            val partial = destinationDirectory.listFiles().orEmpty().firstOrNull { it.name.endsWith(".part") }
            assertTrue("A non-empty partial file must be retained for resume", partial != null && partial.length() > 0L)
            assertTrue("The partial file must not exceed the original size", partial!!.length() < payload.size.toLong())

            val secondReceiver = async {
                UltraShareTransport.receiveOnce(port, pin, destinationDirectory)
            }
            delay(400)
            UltraShareTransport.send(
                resolver = context.contentResolver, uri = Uri.fromFile(source), host = host,
                port = port, pairingSecret = pin, fileName = source.name,
                mimeType = "application/octet-stream", sizeBytes = payload.size.toLong()
            )
            val received = withTimeout(30_000) { secondReceiver.await() }
            assertEquals(payload.size.toLong(), received.sizeBytes)
            assertArrayEquals("Resumed transfer must exactly match the original bytes", payload, received.file.readBytes())
        } finally {
            sourceDirectory.deleteRecursively()
            destinationDirectory.deleteRecursively()
        }
    }

    private fun findPrivateIpv4Address(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: error("CI runner has no network interfaces")
        val address = interfaces.toList()
            .flatMap { network -> network.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isAnyLocalAddress }
        return address?.hostAddress
            ?: throw AssertionError("CI runner has no private IPv4 interface for the TCP transfer test")
    }
}
