# UltraShare Mesh room lifecycle and direct-link orchestration

Branch: `feature/zapya-engineering-analysis`

The room coordinator now participates in live foreground-service orchestration. The service uses the authenticated MPC2 control channel for room setup and creates direct peer links from the verified roster. V2 remains the only media data plane.

## Room and roster lifecycle

- Authority creates a fresh UUID room ID and cryptographically random 32-byte room key.
- KEY_OFFER is delivered only through an approved encrypted MPC2 application-frame session.
- The receiver accepts the key only when the envelope sender matches the authenticated MPC2 peer identity.
- Authority registers members only against authenticated stable peer IDs.
- HMAC-authenticated roster snapshots carry the room membership and private IPv4 candidate routes.
- Joiners verify room ID, authority identity, HMAC, revision, and self-membership before acknowledging the roster.
- Closing the coordinator wipes the in-memory room key and clears membership.

## Direct MPC2 link layer

- Each joined member attempts to open a dedicated direct-link MPC2 listener on port `38746`.
- The listener has no room-wide PIN. It selects a pair-specific 32-byte credential using the stable peer ID announced in the MPC2 request.
- The credential is derived from the room key, room ID, and canonical sorted peer-ID pair. The resolver returns a credential only when both peers exist in the locally authenticated roster.
- The direct server verifies the credential before asking the service to accept the member. The service binds the request identity to the roster and registers the peer as an approved send target.
- The local topology planner computes the complete undirected graph, treats active primary MPC2 sessions as existing edges, and elects one deterministic initiator per missing edge.
- The elected initiator dials the remote peer's direct MPC2 listener. The returned peer identity is checked against the exact roster member before the edge is marked active.
- Direct connection attempts retry transient failures up to three times. A missing route or unavailable listener does not invalidate the room handshake.

## V2 media authentication

- Media bytes continue to travel directly over `UltraShareBatchTransport` V2; the authority does not relay B-to-C media.
- The V2 sender uses the pair-specific credential held by the approved direct send target.
- The V2 receiver resolves the expected credential from the authenticated MPC2 peer identity and active direct-edge state. It does not fall back to the room PIN when a direct-edge credential is expected.
- Existing authority/member sessions continue using their original approved pairing secret.
- Resume checkpoint identity uses the stable authenticated peer ID when available.
- Retry/re-discovery can re-establish a direct MPC2 link with the same roster-derived pair credential.


- MPC2 listener admission is aligned with the room ceiling of 16 participants. The V2 receive server still has its existing four-peer concurrent transfer limit per device; additional inbound data sessions are refused while all four slots are occupied.

## Important boundaries

This is live source integration for direct MPC2 link setup and V2 per-peer credential selection, but it is not yet a verified production Mesh.

- Current roster candidates are the private IPv4 address observed by the room authority, tagged as WLAN. Full multi-interface candidate exchange and robust cross-subnet Wi-Fi Direct/Hotspot routing remain to be completed.
- Control-channel registries are still keyed by remote host/IP, so simultaneous alternate sessions to the same peer address require a future session-ID keyed registry.
- The V2 listener must be running on each participating device. The current pairing flow starts the reverse receive listener for the joining device; the authority must remain in receive mode.
- The new JVM tests cover per-peer MPC2 credential resolution and V2 credential selection, but have not yet been executed in Gradle/CI.
- No three-or-more physical-device test has verified B-to-C direct media delivery, concurrent bidirectional transfers, disconnect recovery, or resume over a direct edge.

Do not mark Mesh complete or publish a release APK until Gradle/CI, Android runtime tests, and physical multi-device transfer tests pass.
