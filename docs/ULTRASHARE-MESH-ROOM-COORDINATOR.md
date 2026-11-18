# UltraShare Mesh room lifecycle coordinator

Branch: `feature/zapya-engineering-analysis`

Adds `UltraShareMeshRoomCoordinator`, a synchronized in-memory state machine for room authority and joiner roles, now connected to the foreground service's MPC2 application-message channel.

## Implemented state transitions

- Authority creates a fresh UUID room ID and cryptographically random 32-byte room key.
- Authority emits KEY_OFFER; the receiver accepts it only when the message sender matches the authenticated MPC2 peer identity.
- Authority registers only peers whose stable identity matches the authenticated MPC2 identity.
- Authority produces HMAC-authenticated roster snapshots as members change.
- Joiner accepts a roster only when room ID, authority identity, HMAC, envelope revision, roster revision, and local membership all match; revisions must strictly increase.
- KEY_ACK and ROSTER_ACK bind SHA-256 digests to the exact key or roster payload.
- Closing the coordinator zeroes its in-memory room key and clears room membership.

## Foreground-service integration now present

- `UltraShareControlChannel.serveOnce` exposes `onSessionReady`, called only after the authenticated ACCEPT frame has been flushed and the application-frame session is ready.
- The service starts one serialized room-handshake job per approved server-side MPC2 session.
- The authority sends KEY_OFFER, verifies KEY_ACK, adds the authenticated peer, sends ROSTER_UPDATE, and verifies ROSTER_ACK.
- The pairing caller notifies the foreground service immediately after client-side MPC2 approval; the service starts the room join at once. The V2 send path retains a guarded fallback if service startup raced with pairing.
- A single client message pump processes later authenticated roster updates and sends acknowledgements. The authority broadcasts newer rosters to its existing approved MPC2 clients.
- Session jobs and in-memory room keys are cleaned up on session/service shutdown.
- Legacy MPC2 peers without application-frame capability remain usable; Mesh setup is skipped for those sessions.

## Remaining integration and verification

This is now live service orchestration for the authority-to-client room exchange, not a complete peer mesh. The server-side session map is still keyed by remote IP. The service does not yet dial all missing topology edges, establish B↔C direct MPC2 sessions, bind per-pair derived credentials to those sockets, or route V2 data directly between non-authority peers. The server currently records authenticated roster ACKs from existing peers; it does not yet reconcile a complete stable-peer-ID session registry.

The added JVM test exercises KEY_OFFER / KEY_ACK / ROSTER_UPDATE / ROSTER_ACK over a real local MPC2 encrypted application-frame socket. Source changes and tests still require Gradle execution/CI confirmation, successful Android build, and at least three physical-device tests before claiming a complete Mesh or publishing an APK.
