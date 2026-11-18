package com.malekplay.network

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Derives a pair-specific MPC2 pairing secret from the authenticated room key.
 *
 * The result is a 32-byte hexadecimal secret accepted by UltraShareCore's
 * existing MPC2 pairing-key path. Both endpoints derive the same value, while
 * a different room or peer pair produces a different value.
 *
 * IMPORTANT: this helper does not establish a link or authorize a peer by
 * itself. Callers must first validate the room roster and authenticated peer
 * identities, then deliver/use the result only for that exact direct link.
 */
internal object UltraShareMeshLinkCredential {
    private const val DOMAIN = "MPULTRA_MESH_DIRECT_LINK_CREDENTIAL"
    private const val VERSION = 1
    private const val KEY_BYTES = 32

    /**
     * Returns 64 lowercase hex characters. Peer ordering is canonical, so A-B
     * and B-A derive identical credentials independent of call direction.
     */
    fun derivePairingSecret(
        roomId: String,
        roomKey: ByteArray,
        firstPeerId: String,
        secondPeerId: String
    ): String {
        require(roomKey.size == KEY_BYTES) { "Mesh room key must contain exactly 32 bytes." }
        val canonicalRoom = canonicalId(roomId)
        val first = canonicalId(firstPeerId)
        val second = canonicalId(secondPeerId)
        require(first != second) { "A direct link cannot connect a peer to itself." }

        val low = minOf(first, second)
        val high = maxOf(first, second)
        val context = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF(DOMAIN)
                out.writeInt(VERSION)
                out.writeUTF(canonicalRoom)
                out.writeUTF(low)
                out.writeUTF(high)
            }
            bytes.toByteArray()
        }
        val keyCopy = roomKey.copyOf()
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(keyCopy, "HmacSHA256"))
            val derived = mac.doFinal(context)
            try {
                require(derived.size == KEY_BYTES)
                derived.joinToString("") { byte ->
                    "%02x".format(java.util.Locale.ROOT, byte.toInt() and 0xff)
                }
            } finally {
                derived.fill(0)
            }
        } finally {
            keyCopy.fill(0)
            context.fill(0)
        }
    }

    /** Constant-time comparison for credentials supplied by another component. */
    fun matches(
        expected: String,
        supplied: String
    ): Boolean {
        if (!isCredential(expected) || !isCredential(supplied)) return false
        return MessageDigest.isEqual(
            expected.lowercase(java.util.Locale.ROOT).toByteArray(Charsets.US_ASCII),
            supplied.lowercase(java.util.Locale.ROOT).toByteArray(Charsets.US_ASCII)
        )
    }

    fun isCredential(value: String): Boolean =
        value.length == KEY_BYTES * 2 &&
            value.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }

    private fun canonicalId(raw: String): String {
        val normalized = raw.replace("-", "").lowercase(java.util.Locale.ROOT)
        require(normalized.length == 32 && normalized.all { it.digitToIntOrNull(16) != null }) {
            "Invalid Mesh room or peer identity."
        }
        return normalized
    }
}
