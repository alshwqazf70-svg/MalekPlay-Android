package com.malekplay.network

import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UltraShareControlChannelTest {
    @Test
    fun eitherApprovedControlSessionDirectionAllowsTheDataChannel() {
        assertTrue(UltraShareControlChannel.isDataSessionAuthorized(true, false))
        assertTrue(UltraShareControlChannel.isDataSessionAuthorized(false, true))
        assertTrue(UltraShareControlChannel.isDataSessionAuthorized(true, true))
        assertFalse(UltraShareControlChannel.isDataSessionAuthorized(false, false))
    }

    @Test
    fun authenticatedRequestWaitsForAndReceivesApproval() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "123456789"
        val stablePeerId = "0123456789abcdef0123456789abcdef"
        val sessionClosed = CompletableDeferred<Unit>()
        val server = async {
            UltraShareControlChannel.serveOnce(
                pairingSecret = secret,
                port = port,
                localPeerName = "Receiver Phone",
                localPeerId = "fedcba9876543210fedcba9876543210",
                onSessionClosed = { sessionClosed.complete(Unit) },
            ) { request ->
                assertEquals("Sender Phone", request.peerName)
                assertTrue(request.peerAddress == "127.0.0.1")
                assertEquals(stablePeerId, request.peerId)
                UltraShareControlChannel.Decision.ACCEPTED
            }
        }
        delay(150)
        val decision = UltraShareControlChannel.request(
            host = "127.0.0.1",
            pairingSecret = secret,
            peerName = "Sender Phone",
            port = port,
            peerId = stablePeerId
        )
        assertEquals(UltraShareControlChannel.Decision.ACCEPTED, decision)
        assertTrue(UltraShareControlChannel.isSessionActive("127.0.0.1"))
        assertTrue(UltraShareControlChannel.isServerSessionActive("127.0.0.1"))
        assertEquals("Receiver Phone", UltraShareControlChannel.sessionPeerName("127.0.0.1"))
        assertEquals("fedcba9876543210fedcba9876543210", UltraShareControlChannel.sessionPeerId("127.0.0.1"))
        assertEquals(stablePeerId, UltraShareControlChannel.serverSessionIdentity("127.0.0.1"))

        // A second malformed peer must be isolated in its own worker and must not
        // tear down the already approved heartbeat session.
        Socket().use { malformed ->
            malformed.connect(java.net.InetSocketAddress("127.0.0.1", port), 1_000)
        }
        delay(150)
        assertTrue(UltraShareControlChannel.isSessionActive("127.0.0.1"))

        UltraShareControlChannel.closeSession("127.0.0.1")
        withTimeout(2_000L) { sessionClosed.await() }
        assertEquals(null, UltraShareControlChannel.serverSessionIdentity("127.0.0.1"))
        server.cancelAndJoin()
    }

    @Test
    fun reconnectingPeerReplacesItsOldSessionWithoutStaleDisconnect() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "246813579"
        val firstPeerId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val secondPeerId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val server = async {
            UltraShareControlChannel.serveOnce(
                pairingSecret = secret,
                port = port,
                localPeerName = "Receiver"
            ) { UltraShareControlChannel.Decision.ACCEPTED }
        }
        delay(150)

        assertEquals(
            UltraShareControlChannel.Decision.ACCEPTED,
            UltraShareControlChannel.request(
                host = "127.0.0.1",
                pairingSecret = secret,
                peerName = "Sender one",
                port = port,
                peerId = firstPeerId
            )
        )
        assertEquals(firstPeerId, UltraShareControlChannel.serverSessionIdentity("127.0.0.1"))
        assertEquals(null, UltraShareControlChannel.sessionPeerId("127.0.0.1"))
        assertTrue(UltraShareControlChannel.isSessionActive("127.0.0.1"))

        assertEquals(
            UltraShareControlChannel.Decision.ACCEPTED,
            UltraShareControlChannel.request(
                host = "127.0.0.1",
                pairingSecret = secret,
                peerName = "Sender two",
                port = port,
                peerId = secondPeerId
            )
        )
        assertEquals(secondPeerId, UltraShareControlChannel.serverSessionIdentity("127.0.0.1"))
        assertTrue(UltraShareControlChannel.isServerSessionActive("127.0.0.1"))
        assertTrue(UltraShareControlChannel.isSessionActive("127.0.0.1"))

        UltraShareControlChannel.closeSession("127.0.0.1")
        assertFalse(UltraShareControlChannel.isSessionActive("127.0.0.1"))
        UltraShareControlChannel.closeServerSession("127.0.0.1")
        server.cancelAndJoin()
    }


    @Test
    fun meshRoomKeyAndRosterHandshakeRunsInsideApprovedEncryptedMpc2Session() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "314159265"
        val authorityId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val joinerId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val authority = UltraShareMeshRoomCoordinator(authorityId)
        val joiner = UltraShareMeshRoomCoordinator(joinerId)
        val authorityJob = CompletableDeferred<kotlinx.coroutines.Job>()
        val server = async {
            UltraShareControlChannel.serveOnce(
                pairingSecret = secret,
                port = port,
                localPeerName = "Mesh authority",
                localPeerId = authorityId,
                onSessionReady = {
                    authorityJob.complete(launch {
                        authority.createAuthorityRoom(
                            UltraShareMeshTopology.Peer(authorityId, "Authority", emptyList()),
                            System.currentTimeMillis().coerceAtLeast(1L)
                        )
                        val offer = authority.createKeyOffer()
                        try {
                            UltraShareControlChannel.sendServerApplicationMessage(
                                "127.0.0.1", UltraShareMeshControlProtocol.encode(offer)
                            )
                            val keyAck = UltraShareMeshControlProtocol.decode(
                                withTimeout(3_000L) {
                                    UltraShareControlChannel.receiveServerApplicationMessage("127.0.0.1")
                                }!!
                            )!!
                            assertTrue(authority.verifyAcknowledgement(
                                joinerId, keyAck, UltraShareMeshControlProtocol.Type.KEY_ACK, offer.payload
                            ))
                        } finally {
                            offer.payload.fill(0)
                        }
                        authority.upsertMember(
                            joinerId,
                            UltraShareMeshTopology.Peer(joinerId, "Joiner", emptyList())
                        )
                        val roster = authority.createRosterUpdate()
                        UltraShareControlChannel.sendServerApplicationMessage(
                            "127.0.0.1", UltraShareMeshControlProtocol.encode(roster)
                        )
                        val rosterAck = UltraShareMeshControlProtocol.decode(
                            withTimeout(3_000L) {
                                UltraShareControlChannel.receiveServerApplicationMessage("127.0.0.1")
                            }!!
                        )!!
                        assertTrue(authority.verifyAcknowledgement(
                            joinerId, rosterAck, UltraShareMeshControlProtocol.Type.ROSTER_ACK,
                            roster.payload
                        ))
                    })
                }
            ) { UltraShareControlChannel.Decision.ACCEPTED }
        }
        delay(150)
        try {
            assertEquals(
                UltraShareControlChannel.Decision.ACCEPTED,
                UltraShareControlChannel.request(
                    host = "127.0.0.1",
                    pairingSecret = secret,
                    peerName = "Mesh joiner",
                    port = port,
                    peerId = joinerId
                )
            )
            val offer = UltraShareMeshControlProtocol.decode(
                withTimeout(3_000L) {
                    UltraShareControlChannel.receiveClientApplicationMessage("127.0.0.1")
                }!!
            )!!
            val keyAck = joiner.acceptKeyOffer(authorityId, offer)
            UltraShareControlChannel.sendClientApplicationMessage(
                "127.0.0.1", UltraShareMeshControlProtocol.encode(keyAck)
            )
            val roster = UltraShareMeshControlProtocol.decode(
                withTimeout(3_000L) {
                    UltraShareControlChannel.receiveClientApplicationMessage("127.0.0.1")
                }!!
            )!!
            val rosterAck = joiner.acceptRosterUpdate(authorityId, roster)
            UltraShareControlChannel.sendClientApplicationMessage(
                "127.0.0.1", UltraShareMeshControlProtocol.encode(rosterAck)
            )
            withTimeout(3_000L) { authorityJob.await().join() }
            assertEquals(2, joiner.state().members.size)
            assertEquals(2, authority.state().members.size)
        } finally {
            authority.close()
            joiner.close()
            UltraShareControlChannel.closeSession("127.0.0.1")
            UltraShareControlChannel.closeServerSession("127.0.0.1")
            server.cancelAndJoin()
        }
    }

    @Test
    fun newPairingSecretCannotReuseAnAlreadyAuthenticatedSession() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val correctSecret = "135791357"
        val peerId = "1234567890abcdef1234567890abcdef"
        val oldSessionClosed = CompletableDeferred<Unit>()
        val server = async {
            UltraShareControlChannel.serveOnce(
                pairingSecret = correctSecret,
                port = port,
                onSessionClosed = { oldSessionClosed.complete(Unit) }
            ) {
                UltraShareControlChannel.Decision.ACCEPTED
            }
        }
        delay(150)

        assertEquals(
            UltraShareControlChannel.Decision.ACCEPTED,
            UltraShareControlChannel.request(
                host = "127.0.0.1",
                pairingSecret = correctSecret,
                peerName = "Known Phone",
                port = port,
                peerId = peerId
            )
        )
        assertTrue(UltraShareControlChannel.isSessionActive("127.0.0.1"))

        // A fresh QR carries a different secret. The old authenticated socket must
        // be retired, and the new attempt must fail rather than inherit old approval.
        val failure = runCatching {
            UltraShareControlChannel.request(
                host = "127.0.0.1",
                pairingSecret = "246802468",
                peerName = "Known Phone",
                port = port,
                peerId = peerId
            )
        }.exceptionOrNull()

        assertTrue("A different invitation secret must fail authentication", failure != null)
        assertFalse(UltraShareControlChannel.isSessionActive("127.0.0.1"))
        withTimeout(2_000L) { oldSessionClosed.await() }
        server.cancelAndJoin()
    }

    @Test
    fun rejectedRequestReturnsEncryptedRejection() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "987654321"
        val server = async {
            UltraShareControlChannel.serveOnce(secret, port) {
                UltraShareControlChannel.Decision.REJECTED
            }
        }
        delay(150)
        val decision = UltraShareControlChannel.request(
            host = "127.0.0.1",
            pairingSecret = secret,
            peerName = "Second Phone",
            port = port
        )
        assertEquals(UltraShareControlChannel.Decision.REJECTED, decision)
        server.cancelAndJoin()
    }
    @Test
    fun approvedMpc2SessionExchangesAuthenticatedApplicationMessagesBothWays() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val secret = "112233445"
        val peerId = "0123456789abcdef0123456789abcdef"
        val sessionReady = CompletableDeferred<UltraShareControlChannel.Request>()
        val clientSessionReady = CompletableDeferred<String>()
        val explicitSessionReady = CompletableDeferred<String>()
        UltraShareControlChannel.setClientSessionReadyListener { host ->
            clientSessionReady.complete(host)
        }
        val server = async {
            UltraShareControlChannel.serveOnce(
                pairingSecret = secret,
                port = port,
                localPeerName = "Receiver",
                localPeerId = "fedcba9876543210fedcba9876543210",
                onSessionReady = { request -> sessionReady.complete(request) }
            ) { UltraShareControlChannel.Decision.ACCEPTED }
        }
        delay(150)
        try {
            assertEquals(
                UltraShareControlChannel.Decision.ACCEPTED,
                UltraShareControlChannel.request(
                    host = "127.0.0.1",
                    pairingSecret = secret,
                    peerName = "Sender",
                    port = port,
                    peerId = peerId,
                    onSessionReady = { readyHost -> explicitSessionReady.complete(readyHost) }
                )
            )
            val readyRequest = withTimeout(3_000L) { sessionReady.await() }
            assertEquals(peerId, readyRequest.peerId)
            assertEquals("127.0.0.1", withTimeout(3_000L) { clientSessionReady.await() })
            assertEquals("127.0.0.1", withTimeout(3_000L) { explicitSessionReady.await() })
            assertTrue(UltraShareControlChannel.supportsServerApplicationFrames("127.0.0.1"))
            assertTrue(UltraShareControlChannel.supportsClientApplicationFrames("127.0.0.1"))
            val outbound = "encrypted client-to-server roster".toByteArray()
            UltraShareControlChannel.sendClientApplicationMessage("127.0.0.1", outbound)
            assertArrayEquals(
                outbound,
                withTimeout(3_000L) {
                    UltraShareControlChannel.receiveServerApplicationMessage("127.0.0.1")
                }
            )
            val inbound = "encrypted server-to-client acknowledgement".toByteArray()
            UltraShareControlChannel.sendServerApplicationMessage("127.0.0.1", inbound)
            assertArrayEquals(
                inbound,
                withTimeout(10_000L) {
                    UltraShareControlChannel.receiveClientApplicationMessage("127.0.0.1")
                }
            )
        } finally {
            UltraShareControlChannel.setClientSessionReadyListener(null)
            UltraShareControlChannel.closeSession("127.0.0.1")
            UltraShareControlChannel.closeServerSession("127.0.0.1")
            server.cancelAndJoin()
        }
    }


    @Test
    fun meshListenerAcceptsOnlyTheCredentialForTheAnnouncedMember() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val roomKey = ByteArray(32) { it.toByte() }
        val authorityId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val memberId = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val roomId = "cccccccccccc4ccc8ccccccccccccccc"
        val secret = UltraShareMeshLinkCredential.derivePairingSecret(
            roomId, roomKey, authorityId, memberId
        )
        val ready = CompletableDeferred<UltraShareControlChannel.Request>()
        val server = async {
            UltraShareControlChannel.serveOnce(
                pairingSecret = null,
                port = port,
                localPeerId = authorityId,
                pairingSecretResolver = { announcedId ->
                    if (announcedId == memberId) secret else null
                },
                onSessionReady = { ready.complete(it) },
                onRequest = { UltraShareControlChannel.Decision.ACCEPTED }
            )
        }
        delay(150)
        try {
            assertEquals(
                UltraShareControlChannel.Decision.ACCEPTED,
                UltraShareControlChannel.request(
                    host = "127.0.0.1",
                    pairingSecret = secret,
                    peerName = "Mesh peer",
                    port = port,
                    peerId = memberId,
                    notifyClientSessionReady = false
                )
            )
            val acceptedRequest = withTimeout(3_000L) { ready.await() }
            assertEquals(memberId, acceptedRequest.peerId)
            assertTrue(acceptedRequest.applicationFramesEnabled)
            assertEquals(memberId, UltraShareControlChannel.serverSessionIdentity("127.0.0.1"))
            assertTrue(
                runCatching {
                    UltraShareControlChannel.request(
                        host = "127.0.0.1",
                        pairingSecret = secret,
                        peerName = "Unlisted peer",
                        port = port,
                        peerId = "dddddddddddddddddddddddddddddddd",
                        notifyClientSessionReady = false
                    )
                }.isFailure
            )
        } finally {
            roomKey.fill(0)
            UltraShareControlChannel.closeSession("127.0.0.1")
            UltraShareControlChannel.closeServerSession("127.0.0.1")
            server.cancelAndJoin()
        }
    }

}
