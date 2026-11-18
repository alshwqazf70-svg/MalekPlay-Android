# MPC2 Authenticated Application Frames — Engineering Note

## Status

This is an isolated codec and unit-test layer. It is **not wired into** `UltraShareControlChannel`, does not change the existing MPC2 wire protocol, and does not yet exchange roster messages between phones. Do not describe application-data encryption or Mesh as active until the integration and device tests pass.

## Frame security design

- AES-256-GCM with a 128-bit authentication tag.
- HKDF-style HMAC-SHA256 extract/expand derives independent client-to-server and server-to-client keys and 4-byte nonce prefixes.
- Derivation is bound to both a fresh 32-byte handshake master secret and a transcript context (16–256 bytes).
- Nonce = directional 4-byte prefix || monotonically increasing 64-bit sequence.
- Authenticated header binds magic, version, direction, nonce prefix, sequence, and ciphertext length.
- Receiver accepts only the exact next sequence; duplicates, gaps, and out-of-order frames are rejected.
- Plaintext is capped at 16 KiB; wire frames are length-bounded before decryption.
- Session close zeroes in-memory key and nonce-prefix arrays.

## Integration prerequisites

1. Extend the authenticated MPC2 handshake to derive a fresh session master secret and transcript context on both peers. The current channel derives a pairing key from the invitation secret and receiver challenge but does not retain a session traffic key after the decision.
2. Keep the new master secret out of logs, diagnostics, discovery, QR payloads, and persistent preferences.
3. Create exactly one frame Session per approved socket; close it in every socket/session cleanup path.
4. Serialize writes per socket and use a single reader/dispatcher so heartbeat and application frames cannot race on the same DataInputStream.
5. Negotiate frame version/capability before sending application frames; old clients must continue using the existing heartbeat-only behavior.
6. Add bounded message types, roster revision handling, acknowledgements, timeout and cancellation semantics.
7. Run Gradle unit tests and at least three physical Android devices before claiming live Mesh support.

## Tests added

Bidirectional exchange, directional key separation, tampering, replay, sequence gaps, wrong secret, transcript mismatch, size bounds, and closed-session behavior.
