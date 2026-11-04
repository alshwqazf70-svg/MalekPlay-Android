package com.malekplay.network

import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class NetworkClientTimeoutsTest {

    @Test
    fun configuresBoundedSshConnectAndSocketTimeouts() {
        val client = SSHClient()
        try {
            NetworkClientTimeouts.configureSsh(client)
            assertEquals(NetworkClientTimeouts.CONNECT_TIMEOUT_MS, client.connectTimeout)
            assertEquals(NetworkClientTimeouts.SOCKET_TIMEOUT_MS, client.timeout)
        } finally {
            client.close()
        }
    }

    @Test
    fun configuresBoundedSmbSocketAndOperationTimeouts() {
        val client = NetworkClientTimeouts.createSmbClient()
        client.close()
        val config = com.hierynomus.smbj.SmbConfig.builder()
            .withTimeout(NetworkClientTimeouts.SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .withSoTimeout(NetworkClientTimeouts.SOCKET_TIMEOUT_MS)
            .build()
        assertEquals(NetworkClientTimeouts.SOCKET_TIMEOUT_MS.toLong(), config.readTimeout)
        assertEquals(NetworkClientTimeouts.SOCKET_TIMEOUT_MS.toLong(), config.writeTimeout)
        assertEquals(NetworkClientTimeouts.SOCKET_TIMEOUT_MS.toLong(), config.transactTimeout)
        assertEquals(NetworkClientTimeouts.SOCKET_TIMEOUT_MS, config.soTimeout)
    }
}
