# UltraShare Mesh MPC2 control protocol — phase 3

Branch: `feature/zapya-engineering-analysis`

This phase adds a typed, bounded envelope for Mesh room-control messages and connects the room handshake to the foreground service's authenticated MPC2 application-frame sessions. The envelope itself is not a substitute for MPC2 AES-GCM authentication, sequence enforcement, or peer approval.

## Message types

- `KEY_OFFER`: exactly 32 bytes of fresh room-key material, carried inside the encrypted MPC2 application frame.
- `KEY_ACK`: SHA-256 digest acknowledging the accepted key offer.
- `ROSTER_UPDATE`: UTF-8 `MPMESH|1|` authenticated roster wire payload, bounded to 12 KiB.
- `ROSTER_ACK`: SHA-256 digest acknowledging the exact roster payload.

Every message binds a canonical 128-bit room ID, canonical sender peer ID, positive revision, type, and bounded payload. Unknown types, malformed IDs, non-positive revisions, invalid payload sizes, oversized messages, and trailing bytes are rejected.

## Service integration

- The server-side session-ready callback fires only after ACCEPT is flushed.
- Pairing UI notifies/starts the foreground service immediately after client-side MPC2 approval, so the room handshake does not wait for the first media transfer.
- The service serializes authority room handshakes so concurrent peer joins cannot race room revisions.
- Each joining peer receives the room key only through its approved encrypted MPC2 session and must return a verified KEY_ACK.
- The authority updates the authenticated roster and requires a verified ROSTER_ACK from the joining peer.
- Existing capable clients receive newer roster revisions through their own single client-side message pump and return ROSTER_ACK.
- Message envelopes are bound to the stable peer identity authenticated by the corresponding MPC2 session.
- Room state is in-memory only; session/service cleanup cancels pumps and closes the coordinator, which wipes its room key.

## Security and lifecycle boundary

- Never put the room key in discovery broadcasts, QR codes, diagnostics, logs, preferences, or files.
- A KEY_ACK / ROSTER_ACK digest must match the exact payload sent; a revision must never roll back.
- Roster HMAC validation remains mandatory after envelope decoding.
- Keep one service-owned receive consumer per MPC2 session; multiple consumers would race on the same incoming channel.
- Legacy peers without negotiated application-frame support do not enter the Mesh room protocol.

## What this phase does not claim

The room-control exchange is integrated, but the app does not yet create all missing direct topology edges, establish B↔C direct links, use the pair-specific derived credential for those links, or verify direct V2 file delivery between non-authority peers. The server session registry remains host/IP keyed. The new end-to-end JVM test is source only until Gradle runs it. Do not mark Mesh complete or publish a release APK until CI/build and multi-device transfer tests pass.
