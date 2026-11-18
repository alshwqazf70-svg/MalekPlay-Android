# UltraShare Mesh roster protocol checkpoint

Branch: \`feature/zapya-engineering-analysis\`

Adds the versioned, bounded HMAC-authenticated room-membership snapshot codec and JVM tests.

## Wire contract

- Frame prefix: \`MPMESH|1|\`
- Includes room ID, issuer stable peer ID, revision, creation timestamp, and 2–16 members.
- Each member carries a stable 128-bit identity, display name, and up to eight reachable private-LAN route candidates.
- HMAC-SHA256 authenticates the exact binary payload using a domain-separated key.
- Duplicate identities, invalid routes, malformed frames, unknown transports, and tampering are rejected.
- IDs are canonicalized to 32 lowercase hexadecimal characters.

## Key handling and integration boundary

The codec requires a fresh random 32-byte room key. It must be provisioned only inside an authenticated and encrypted MPC2 session, never via UDP discovery, QR payloads, logs, or persistent preferences.

The current MPC2 channel supports heartbeat and close after approval; it does not yet expose an encrypted application-data frame API or retain a per-session traffic key. Therefore this codec is not wired into live rooms and does not claim peer rosters are currently exchanged.

## Next integration requirements

1. Upgrade MPC2 with a version-negotiated, length-bounded encrypted application frame.
2. Derive directional traffic keys and unique nonces per session; reject replayed sequence numbers.
3. Deliver the room key inside that protected channel.
4. Bind each roster issuer to the authenticated session peer ID and reject revision rollback.
5. Publish roster changes when peers join, leave, or change routes.
6. Feed authenticated rosters to the mesh topology planner and establish direct links.
7. Add multi-device integration tests and physical-device validation.

Do not mark Mesh complete or release an APK until these runtime steps and tests pass.
