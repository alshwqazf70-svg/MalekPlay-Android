# UltraShare Mesh room lifecycle coordinator

Branch: `feature/zapya-engineering-analysis`

Adds `UltraShareMeshRoomCoordinator`, a synchronized in-memory state machine for room authority and joiner roles.

## Implemented state transitions

- Authority creates a fresh UUID room ID and cryptographically random 32-byte room key.
- Authority emits KEY_OFFER; the receiver accepts it only when the message sender matches the authenticated MPC2 peer identity.
- Authority registers only peers whose stable identity matches the authenticated MPC2 identity.
- Authority produces HMAC-authenticated roster snapshots as members change.
- Joiner accepts a roster only when room ID, authority identity, HMAC, envelope revision, roster revision, and local membership all match; revisions must strictly increase.
- KEY_ACK and ROSTER_ACK bind SHA-256 digests to the exact key or roster payload.
- Closing the coordinator zeroes its in-memory room key and clears room membership.

## Integration contract

The coordinator intentionally does not open sockets or launch coroutines. The service must call it only from a single receive consumer for each approved MPC2 session, after matching the message sender to `sessionPeerId` / authenticated server request identity. It must send the returned ACK through the same approved MPC2 session. The key offer and roster update must never be passed through discovery, QR, logs, or persistent storage.

## Not yet complete

This is a testable room lifecycle component, not proof of live service integration or a complete mesh. The foreground service still needs per-session consumers and dispatch, room authority election/coordination for multi-hop joins, stable peer-ID keyed session registry, topology edge dialing, and direct data-plane verification. A room roster alone does not create B↔C sockets. Run Gradle unit tests and a successful Android build, then validate at least three physical devices before release.
