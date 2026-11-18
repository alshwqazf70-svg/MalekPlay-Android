# UltraShare Mesh roster protocol checkpoint

Branch: `feature/zapya-engineering-analysis`

Adds a versioned, bounded HMAC-authenticated room-membership snapshot codec and JVM tests.

## Roster wire contract

- Frame prefix: `MPMESH|1|`
- Includes room ID, issuer stable peer ID, revision, creation timestamp, and 2–16 members.
- Each member carries a stable 128-bit identity, display name, and up to eight reachable private-LAN route candidates.
- HMAC-SHA256 authenticates the exact binary payload using a domain-separated 32-byte room key.
- Duplicate identities, invalid routes, malformed frames, unknown transports, rollback values, and tampering are rejected by the codec's structural checks and its caller's revision policy.
- IDs are canonicalized to 32 lowercase hexadecimal characters.

## MPC2 application transport

MPC2 now negotiates an application-frame capability while preserving the legacy all-hex request ID format. Approved sessions can exchange bounded AES-GCM authenticated messages in both directions, with directional traffic keys, transcript binding, sequence numbers, replay/out-of-order rejection, and bounded receive queues.

The new `UltraShareMeshControlProtocol` defines typed KEY_OFFER, KEY_ACK, ROSTER_UPDATE, and ROSTER_ACK envelopes for those encrypted messages. The envelope binds room ID, sender peer ID, positive revision, message type, and bounded payload. The room key is allowed only in KEY_OFFER and must travel inside the encrypted MPC2 application channel.

## Remaining live integration

The codecs and transport are not yet a complete room synchronizer. The foreground service still needs a single receive consumer per approved MPC2 session, authenticated identity binding, room-authority/key lifecycle, roster revision/ACK handling, and topology edge orchestration. The server-side session registry currently uses remote host addresses, so stable peer-ID keyed session management is still required to handle route/IP changes safely.

No claim is made that a complete mesh or direct B-to-C file transfer is active. Require a successful Gradle build, CI, three-device integration tests, and physical-device validation before release.
