package com.malekplay.network

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Short-lived mutual-authentication channel, separate from the file-transfer socket.
 * The numeric PIN is never sent over the LAN; each connection proves knowledge of it
 * against a fresh receiver challenge. The listener remains active only while receiving.
 */
object UltraSharePairing {
    const val PORT = 38743
    private const val MAGIC = 0x4D505032 // MPP2
    private const val CHALLENGE_BYTES = 32
    private const val TIMEOUT_MS = 5_000

    suspend fun connect(
        host: String,
        pairingSecret: String,
        connectivityManager: android.net.ConnectivityManager? = null
    ): String = withContext(Dispatchers.IO) {
        require(UltraShareTransport.isValidPairingSecret(pairingSecret)) {
            "رمز الاقتران يجب أن يتكون من 12 رقماً."
        }
        require(UltraShareTransport.isValidPrivateLanHost(host)) {
            "عنوان الجهاز ليس عنواناً محلياً صالحاً."
        }
        val address = InetAddress.getByName(host)
        UltraShareTransport.connectUsingAvailableNetworks(address, PORT, connectivityManager).use { socket ->
            socket.soTimeout = TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            require(input.readInt() == MAGIC) { "الجهاز لا يستجيب لخدمة اقتران MalekPlay." }
            val challenge = ByteArray(CHALLENGE_BYTES).also(input::readFully)
            val key = UltraShareTransport.derivePairingKey(pairingSecret, challenge)
            val clientProof = UltraShareTransport.hmac(
                key,
                "MalekPlay Ultra Share pairing/client/v1".toByteArray(Charsets.UTF_8) + challenge
            )
            output.write(clientProof)
            output.flush()
            require(input.readBoolean()) { "رفض الجهاز رمز الاقتران. تحقق من الرمز الظاهر على الجهاز المستقبل." }
            val serverProof = ByteArray(32).also(input::readFully)
            val expected = UltraShareTransport.hmac(
                key,
                "MalekPlay Ultra Share pairing/server/v1".toByteArray(Charsets.UTF_8) + challenge
            )
            require(MessageDigest.isEqual(serverProof, expected)) {
                "تعذر التحقق من هوية الجهاز المستقبل."
            }
            host
        }
    }

    suspend fun serveWhileActive(
        pairingSecret: String,
        onPaired: (String) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(UltraShareTransport.isValidPairingSecret(pairingSecret))
        ServerSocket().use { server ->
            server.reuseAddress = true
            server.bind(InetSocketAddress(PORT), 4)
            server.soTimeout = 1_000
            while (true) {
                currentCoroutineContext().ensureActive()
                val socket = try {
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                socket.use { peer ->
                    try {
                        if (peer.inetAddress.isSiteLocalAddress || peer.inetAddress.isLinkLocalAddress) {
                            peer.soTimeout = TIMEOUT_MS
                            peer.tcpNoDelay = true
                            val input = DataInputStream(peer.getInputStream())
                            val output = DataOutputStream(peer.getOutputStream())
                            val challenge = ByteArray(CHALLENGE_BYTES).also(SecureRandom()::nextBytes)
                            val key = UltraShareTransport.derivePairingKey(pairingSecret, challenge)
                            output.writeInt(MAGIC)
                            output.write(challenge)
                            output.flush()
                            val supplied = ByteArray(32).also(input::readFully)
                            val expected = UltraShareTransport.hmac(
                                key,
                                "MalekPlay Ultra Share pairing/client/v1".toByteArray(Charsets.UTF_8) + challenge
                            )
                            val accepted = MessageDigest.isEqual(supplied, expected)
                            output.writeBoolean(accepted)
                            if (accepted) {
                                val proof = UltraShareTransport.hmac(
                                    key,
                                    "MalekPlay Ultra Share pairing/server/v1".toByteArray(Charsets.UTF_8) + challenge
                                )
                                output.write(proof)
                            }
                            output.flush()
                            if (accepted) {
                                onPaired(peer.inetAddress.hostAddress ?: "جهاز قريب")
                                // Pairing authorization is single-use; the file-transfer
                                // listener remains available on its separate port.
                                return@withContext
                            }
                        }
                    } catch (_: Exception) {
                        // Invalid or interrupted pairing attempts do not stop the receiver.
                    }
                }
            }
        }
    }
}
