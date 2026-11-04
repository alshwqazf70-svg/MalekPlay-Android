package com.malekplay.network

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import net.schmizz.sshj.SSHClient
import java.util.concurrent.TimeUnit

/**
 * Bounded connection and socket timeouts for remote media protocols.
 * Prevents a dead or unreachable server from holding a player/listing operation forever.
 */
internal object NetworkClientTimeouts {
    const val CONNECT_TIMEOUT_MS = 15_000
    const val SOCKET_TIMEOUT_MS = 30_000

    fun createSmbClient(): SMBClient = SMBClient(
        SmbConfig.builder()
            .withTimeout(SOCKET_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .withSoTimeout(SOCKET_TIMEOUT_MS)
            .build()
    )

    fun configureSsh(client: SSHClient) {
        client.setConnectTimeout(CONNECT_TIMEOUT_MS)
        client.setTimeout(SOCKET_TIMEOUT_MS)
    }
}
