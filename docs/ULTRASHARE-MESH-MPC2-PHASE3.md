# UltraShare Mesh MPC2 control protocol — phase 3

Branch: `feature/zapya-engineering-analysis`

This phase adds a typed, bounded envelope for Mesh room-control messages. These messages are intended to travel only through the already authenticated MPC2 application-frame channel. The envelope itself is not a substitute for MPC2 AES-GCM authentication, sequence enforcement, or peer approval.

## Message types

- `KEY_OFFER`: exactly 32 bytes of fresh room-key material, carried inside the encrypted MPC2 application frame.
- `KEY_ACK`: SHA-256 digest acknowledging the accepted key offer.
- `ROSTER_UPDATE`: UTF-8 `MPMESH|1|` authenticated roster wire payload, bounded to 12 KiB.
- `ROSTER_ACK`: SHA-256 digest acknowledging the exact roster payload.

Every message binds a canonical 128-bit room ID, canonical sender peer ID, positive revision, type, and bounded payload. Unknown types, malformed IDs, non-positive revisions, invalid payload sizes, oversized messages, and trailing bytes are rejected.

## Security and lifecycle boundary

- A room key must be generated from fresh cryptographically secure random bytes by the room authority and delivered only as a `KEY_OFFER` over an approved MPC2 session.
- Never put the room key in discovery broadcasts, QR codes, diagnostics, logs, preferences, or files.
- The receiver must bind `senderPeerId` to the authenticated MPC2 session identity before processing any message.
- A `KEY_ACK` / `ROSTER_ACK` digest must match the exact payload sent; a revision must never roll back.
- Roster HMAC validation remains mandatory after envelope decoding.
- Keep one service-owned receive consumer per MPC2 session; multiple consumers would race on the same incoming channel.

## What this phase does not claim

The typed message codec and JVM tests are groundwork for live room orchestration. They do not yet generate or distribute a room key, run per-session receive consumers, synchronize roster revisions, establish missing topology edges, or prove direct B-to-C file delivery. The current app's server-side session map is keyed by remote IP, and a stable peer-ID keyed session registry is still required before a complete mesh can be trusted.

Do not mark Mesh complete or publish a release APK until service integration, three-device tests, Gradle build, and physical-device transfer tests pass.
