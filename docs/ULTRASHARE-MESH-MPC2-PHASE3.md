# UltraShare Mesh MPC2 protocol — direct-link integration

Branch: `feature/zapya-engineering-analysis`

This phase extends the authenticated room exchange into direct peer control sessions. Room keys and roster messages remain confined to approved encrypted MPC2 application frames; media bytes remain on V2.

## Direct-link authentication

- Every joined device opens a dedicated direct MPC2 listener on port `38746`.
- The listener extracts the stable peer ID from the capability-enabled MPC2 request and asks a resolver for that exact peer's room-derived credential.
- The resolver derives a pair-specific HMAC-SHA256 credential only when the remote ID and local ID are both in the authenticated room roster.
- Unknown identities, absent roster membership, invalid credentials, and mismatched remote identities are rejected.
- Direct sessions suppress the initial room-join callback; a direct peer is not mistaken for the room authority.
- The topology planner uses active authority links as existing edges and schedules only missing direct links, with a deterministic initiator for each edge.

## V2 data-plane binding

- V2 sends to a direct peer use the same pair-specific credential as its direct MPC2 link.
- V2 receive selects the direct credential using the authenticated peer identity associated with the source host.
- A direct edge with a missing credential fails closed rather than falling back to the room PIN.
- Legacy/authority control sessions continue to use their existing pairing secret.
- Stable peer identity scopes V2 receive resume state when a live MPC2 identity is available.
- Reconnection uses the direct listener and pair-specific credential when the remote peer remains in the active roster.

## Tests added (not yet executed)

- MPC2 accepts a per-peer credential resolver and authenticates only the announced stable peer identity.
- V2 credential selection uses the direct peer credential when supplied and rejects a missing resolver result instead of falling back.
- Existing tests cover pair-key derivation, deterministic topology planning, room roster authentication, and encrypted MPC2 application frames.


- MPC2 listener admission is aligned with the room ceiling of 16 participants. The V2 receive server still has its existing four-peer concurrent transfer limit per device; additional inbound data sessions are refused while all four slots are occupied.

## Remaining validation

- Gradle unit tests and Android lint/build have not been confirmed.
- No physical three-phone test has yet verified the B↔C direct link and V2 media transfer.
- Candidate exchange currently contains only the private IPv4 address observed by the authority; multi-interface WLAN/Hotspot/Wi-Fi Direct route exchange is still outstanding.
- Host/IP keyed session registries remain a limitation for multiple simultaneous sessions sharing one remote address.
- Verify that all peers keep the V2 receiver active before treating the room as full-duplex.

A source-level direct-link path is now present, but do not describe it as fully tested or release-ready until CI and physical-device validation pass.
