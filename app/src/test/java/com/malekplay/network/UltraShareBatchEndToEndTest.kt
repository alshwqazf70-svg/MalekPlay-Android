package com.malekplay.network

import android.net.Uri
import java.io.File
import java.net.NetworkInterface
import java.net.ServerSocket
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UltraShareBatchEndToEndTest {
    @Test
    fun approvedKotlinSenderTransfersAndPublishesMultipleMediaFilesOverTcp() = runBlocking {
        val host = NetworkInterface.getNetworkInterfaces().toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress ?: return@runBlocking

        fun freePort(): Int = ServerSocket(0).use { it.localPort }
        val controlPort = freePort()
        val receivedDirectory = kotlin.io.path.createTempDirectory("ultrashare-kotlin-e2e").toFile()
        val sourceDirectory = kotlin.io.path.createTempDirectory("ultrashare-kotlin-source").toFile()
        val receiverResult = CompletableDeferred<UltraShareBatchTransport.BatchOutcome>()
        val controlReady = CompletableDeferred<Unit>()
        val dataReady = CompletableDeferred<Int>()
        val secret = "123456789"
        val receiver = launch(Dispatchers.IO) {
            UltraShareBatchTransport.serveMany(
                port = 0, pairingSecret = secret, destinationDirectory = receivedDirectory,
                onListening = { if (!dataReady.isCompleted) dataReady.complete(it) },
                onBatchFinished = { _, result -> if (!receiverResult.isCompleted) receiverResult.complete(result) }
            )
        }
        val control = launch(Dispatchers.IO) {
            UltraShareControlChannel.serveOnce(
                pairingSecret = secret, port = controlPort,
                onListening = { if (!controlReady.isCompleted) controlReady.complete(Unit) },
                onRequest = { UltraShareControlChannel.Decision.ACCEPTED }
            )
        }
        try {
            withTimeout(5000) { dataReady.await(); controlReady.await() }
            val dataPort = dataReady.await()
            assertEquals(UltraShareControlChannel.Decision.ACCEPTED,
                UltraShareControlChannel.request(host, secret, "Kotlin E2E sender", controlPort))

            val mp4Bytes = ByteArray(1_350_000) { index -> (index * 31).toByte() }
            byteArrayOf(0, 0, 0, 24).copyInto(mp4Bytes, 0)
            "ftypisom".toByteArray().copyInto(mp4Bytes, 4)
            val secondMp4 = ByteArray(740_000) { index -> (index * 17).toByte() }
            byteArrayOf(0, 0, 0, 24).copyInto(secondMp4, 0)
            "ftypmp42".toByteArray().copyInto(secondMp4, 4)
            val firstSource = File(sourceDirectory, "first.mp4").apply { writeBytes(mp4Bytes) }
            val secondSource = File(sourceDirectory, "second.mp4").apply { writeBytes(secondMp4) }
            val resolver = RuntimeEnvironment.getApplication<android.app.Application>().contentResolver
            val sent = UltraShareBatchTransport.sendBatch(
                resolver = resolver,
                sources = listOf(
                    UltraShareBatchTransport.Source(Uri.fromFile(firstSource), "first.mp4", "video/mp4", firstSource.length()),
                    UltraShareBatchTransport.Source(Uri.fromFile(secondSource), "second.mp4", "video/mp4", secondSource.length())
                ),
                host = host, port = dataPort, pairingSecret = secret
            )
            val received = withTimeout(20_000) { receiverResult.await() }
            assertEquals(2, sent.files.size)
            assertTrue(sent.files.all { it.status == UltraShareBatchProtocol.FileStatus.COMPLETED })
            assertEquals(2, received.files.size)
            assertTrue(received.files.all { it.status == UltraShareBatchProtocol.FileStatus.COMPLETED })
            for ((name, expected) in listOf("first.mp4" to mp4Bytes, "second.mp4" to secondMp4)) {
                val actual = File(receivedDirectory, name)
                assertTrue("Missing published file: $name", actual.isFile)
                assertArrayEquals(expected, actual.readBytes())
                assertArrayEquals(
                    MessageDigest.getInstance("SHA-256").digest(expected),
                    MessageDigest.getInstance("SHA-256").digest(actual.readBytes())
                )
            }
        } finally {
            UltraShareControlChannel.closeSession(host)
            control.cancelAndJoin()
            receiver.cancelAndJoin()
            sourceDirectory.deleteRecursively()
            receivedDirectory.deleteRecursively()
        }
    }
}
