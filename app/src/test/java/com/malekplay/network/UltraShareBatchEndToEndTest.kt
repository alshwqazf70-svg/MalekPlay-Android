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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

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
            ?.hostAddress ?: error("No private IPv4 interface available for the Kotlin batch integration test.")

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
    @Test
    fun kotlinSenderInteroperatesWithPythonV2ReceiverOverRealTcp() = runBlocking {
        assumeTrue("Enable cross-language socket test in CI", System.getenv("ULTRASHARE_CROSS_LANG") == "1")
        val host = NetworkInterface.getNetworkInterfaces().toList()
            .asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress ?: error("No private IPv4 interface available for the cross-language test.")
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
        val controlPort = freePort()
        var dataPort = freePort()
        while (dataPort == controlPort) dataPort = freePort()
        val root = File(System.getProperty("user.dir")).absoluteFile
        val script = File(root, "desktop/ultra_share_v2_cross_language_receiver.py")
        check(script.isFile) { "Missing Python V2 receiver test harness: $script" }
        val temp = kotlin.io.path.createTempDirectory("ultrashare-cross-language").toFile()
        val output = File(temp, "received")
        val source = File(temp, "python-interop.mp4")
        val readyControl = File(temp, "control.ready")
        val readyData = File(temp, "data.ready")
        val resultFile = File(temp, "result.json")
        val logFile = File(temp, "python.log")
        val secret = "123456789"
        val payload = ByteArray(1_400_000) { index -> (index * 29).toByte() }.also { bytes ->
            byteArrayOf(0, 0, 0, 24).copyInto(bytes, 0)
            "ftypisom".toByteArray().copyInto(bytes, 4)
        }
        source.writeBytes(payload)
        val process = ProcessBuilder(
            System.getenv("PYTHON")?.takeIf { it.isNotBlank() } ?: "python3",
            script.absolutePath,
            "--control-port", controlPort.toString(),
            "--data-port", dataPort.toString(),
            "--secret", secret,
            "--output", output.absolutePath,
            "--control-ready", readyControl.absolutePath,
            "--data-ready", readyData.absolutePath,
            "--result", resultFile.absolutePath
        ).directory(root).redirectErrorStream(true).redirectOutput(logFile).start()
        try {
            withTimeout(15_000) {
                while (!readyControl.isFile || !readyData.isFile) {
                    if (!process.isAlive) error("Python receiver exited early: ${logFile.readText()}")
                    delay(50)
                }
            }
            assertEquals(UltraShareControlChannel.Decision.ACCEPTED,
                UltraShareControlChannel.request(host, secret, "Kotlin to Python", controlPort))
            val resolver = RuntimeEnvironment.getApplication<android.app.Application>().contentResolver
            val sent = UltraShareBatchTransport.sendBatch(
                resolver,
                listOf(UltraShareBatchTransport.Source(Uri.fromFile(source), source.name, "video/mp4", source.length())),
                host, dataPort, secret
            )
            assertEquals(1, sent.files.size)
            assertEquals(UltraShareBatchProtocol.FileStatus.COMPLETED, sent.files.single().status)
            val exitCode = withContext(Dispatchers.IO) {
                if (process.waitFor(25, TimeUnit.SECONDS)) process.exitValue() else -1
            }
            assertEquals("Python receiver failed: ${logFile.readText()}", 0, exitCode)
            val report = resultFile.readText()
            assertTrue("Python did not report a completed file: $report", report.contains("\"status\": 2"))
            assertArrayEquals(payload, File(output, source.name).readBytes())
        } finally {
            UltraShareControlChannel.closeSession(host)
            if (process.isAlive) process.destroyForcibly()
            temp.deleteRecursively()
        }
    }
    @Test
    fun pythonSenderInteroperatesWithKotlinV2ReceiverOverRealTcp() = runBlocking {
        assumeTrue("Enable cross-language socket test in CI", System.getenv("ULTRASHARE_CROSS_LANG") == "1")
        val host = NetworkInterface.getNetworkInterfaces().toList()
            .asSequence().filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList().asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress ?: error("No private IPv4 interface available for the cross-language test.")
        fun freePort(): Int = ServerSocket(0).use { it.localPort }
        val controlPort = freePort()
        val receivedDirectory = kotlin.io.path.createTempDirectory("ultrashare-python-to-kotlin").toFile()
        val temp = kotlin.io.path.createTempDirectory("ultrashare-python-sender").toFile()
        val source = File(temp, "python-sender.mp4")
        val resultFile = File(temp, "result.json")
        val logFile = File(temp, "python.log")
        val receiverResult = CompletableDeferred<UltraShareBatchTransport.BatchOutcome>()
        val controlReady = CompletableDeferred<Unit>()
        val dataReady = CompletableDeferred<Int>()
        val secret = "123456789"
        val payload = ByteArray(1_100_000) { index -> (index * 13).toByte() }.also { bytes ->
            byteArrayOf(0, 0, 0, 24).copyInto(bytes, 0)
            "ftypmp42".toByteArray().copyInto(bytes, 4)
        }
        source.writeBytes(payload)
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
        var process: Process? = null
        try {
            withTimeout(5_000) { dataReady.await(); controlReady.await() }
            val script = File(File(System.getProperty("user.dir")).absoluteFile,
                "desktop/ultra_share_v2_cross_language_sender.py")
            check(script.isFile) { "Missing Python V2 sender test harness: $script" }
            val runningProcess = ProcessBuilder(
                System.getenv("PYTHON")?.takeIf { it.isNotBlank() } ?: "python3",
                script.absolutePath,
                "--host", host,
                "--control-port", controlPort.toString(),
                "--data-port", dataReady.await().toString(),
                "--secret", secret,
                "--source", source.absolutePath,
                "--result", resultFile.absolutePath
            ).directory(File(System.getProperty("user.dir")).absoluteFile)
                .redirectErrorStream(true).redirectOutput(logFile).start()
            process = runningProcess
            val exitCode = withContext(Dispatchers.IO) {
                if (runningProcess.waitFor(30, TimeUnit.SECONDS)) runningProcess.exitValue() else -1
            }
            assertEquals("Python sender failed: ${logFile.readText()}", 0, exitCode)
            val result = withTimeout(15_000) { receiverResult.await() }
            assertEquals(1, result.files.size)
            assertEquals(UltraShareBatchProtocol.FileStatus.COMPLETED, result.files.single().status)
            assertArrayEquals(payload, File(receivedDirectory, source.name).readBytes())
            val report = resultFile.readText()
            assertTrue("Python sender did not report success: $report", report.contains("\"status\": 2"))
        } finally {
            process?.takeIf { it.isAlive }?.destroyForcibly()
            control.cancelAndJoin()
            receiver.cancelAndJoin()
            temp.deleteRecursively()
            receivedDirectory.deleteRecursively()
        }
    }
}
