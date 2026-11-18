# MPC2 Authenticated Application Frames — Engineering Note

## Status

The bounded AES-GCM frame codec is now integrated into approved MPC2 client/server sessions on this development branch. Both endpoints can send and receive authenticated application messages over the same approved TCP control socket. A channel-level integration test covers client-to-server and server-to-client exchange.

This is an internal control-message channel, not a file-data path. Video and audio bytes continue to use UltraShareBatchTransport V2.

## Negotiation and backward compatibility

- New clients place the `MPC2APP1` capability marker in the authenticated request ID while preserving the final 32-hex stable peer token when present.
- A new server echoes the marker in the encrypted ACCEPT payload only when the connecting client advertised support.
- A new client enables application frames only when the encrypted ACCEPT response echoes the marker.
- Older peers can continue pairing and heartbeat operation without receiving APP frames. The new APIs reject sends when the peer did not negotiate support.
- The existing MPC2 greeting/version and legacy PING/PONG behavior remain unchanged.

## Frame security design

- AES-256-GCM with a 128-bit authentication tag.
- HMAC-SHA256 extract/expand derives independent client-to-server and server-to-client keys and 4-byte nonce prefixes.
- Derivation is bound to a 32-byte master derived from the authenticated pairing key and to a transcript containing protocol version, challenge, request ID, and peer name.
- Nonce = directional 4-byte prefix || monotonically increasing 64-bit sequence.
- Authenticated header binds magic, version, direction, nonce prefix, sequence, and ciphertext length.
- Receiver accepts only the exact next sequence; duplicates, gaps, and out-of-order frames are rejected.
- Plaintext is capped at 16 KiB; wire frames are length-bounded before decryption.
- Per-session writes are serialized with frame sequence allocation, preventing concurrent senders from writing sequence N+1 before N.
- A single reader owns each socket input stream. The client reader also dispatches APP frames encountered while awaiting heartbeat PONG.
- Inbound application queues are bounded to 32 messages; overflow closes the session rather than silently dropping authenticated control messages.
- Session close zeroes in-memory frame keys and nonce-prefix arrays.

## Current API

- `sendClientApplicationMessage(host, payload)`
- `receiveClientApplicationMessage(host)`
- `sendServerApplicationMessage(host, payload)`
- `receiveServerApplicationMessage(host)`

The caller must encode bounded application message types (for example roster update/acknowledgement) and run a receive coroutine for each active session. The channel does not yet automatically publish or reconcile Mesh rosters.

## Remaining integration and validation

1. Connect roster update/acknowledgement messages to the service's approved-peer lifecycle.
2. Add explicit message type/version schemas, roster revision conflict handling, and acknowledgement timeouts.
3. Run Gradle unit tests and static analysis on the branch; GitHub status/workflow results are not yet confirmed.
4. Test with at least three physical Android devices, including simultaneous peers, disconnect/reconnect, and old/new version combinations.
5. Verify no application message is sent over an unnegotiated legacy session.

Do not describe full Mesh routing or peer-to-peer relay as complete. This change enables secure bidirectional control messages only; it does not create B↔C data routes or change the batch data plane.
