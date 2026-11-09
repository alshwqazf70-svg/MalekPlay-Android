# Ultra Share v2 implementation checkpoint

Date: 2026-11-09
Branch: `feature/zapya-engineering-analysis`

## Implemented in this checkpoint

- Added `UltraShareNegotiation.kt` with a version-2, secret-free peer offer model.
- Added validated WLAN, Wi-Fi Direct, and hotspot endpoint candidate records.
- Added deterministic candidate selection: reachable Wi-Fi Direct first, then WLAN, then hotspot; unreachable candidates are excluded.
- Added a local invitation state model with pending, accepted, rejected, expired, and cancelled states.
- Added bounded batch-manifest and per-file result models.
- Added JVM unit tests for offer encoding/decoding, malformed offers, candidate selection, invitation transitions, and batch validation.
- Added an approved-peer session registry to the foreground service. The receiver records the authenticated sender only after explicit acceptance; the sender records an approved receiver only after the encrypted ACCEPT response. Sessions can be explicitly ended from the room.
- Added a device-room section showing approved peers separately from merely discovered peers, with avatar initials, peer name/address, role, authorization status, and an end-session action. Discovery rows now identify devices as discovered rather than connected.
- Added per-file live progress, transferred/total bytes, and throughput to the queue rows. The existing durable queue continues to retain completed/failed/pending/skipped states independently.
- Verified by source inspection that the current receiver stores partial files with content-derived identities, verifies their SHA-256 digest, resumes from the stored byte offset, and recognizes already completed receipts to avoid retransmitting completed files.
- Updated LAN discovery to send both v2 and v1 probes. New receivers answer v2 with available local interface candidates and continue answering v1 for compatibility. New scanners prefer v2 offers and retain v1-only peers. P2P/hotspot interfaces are advertised as candidates but deliberately marked unverified, so they are not auto-selected merely because an interface exists.

## Important limitations — not yet complete

- Added an authenticated TCP control channel on port 38744. The receiver publishes a pending request to the service/UI, and the sender waits for an authenticated AES-GCM protected accept/reject decision before the UI enables the existing encrypted file-transfer path. Request authentication uses a fresh server challenge, PBKDF2-derived key material, HMAC-SHA256, and domain-separated decision encryption keys. The receiver times out unanswered requests after 30 seconds.
- The v2 offer is currently exchanged over LAN UDP only. Wi-Fi Direct and hotspot candidates are advertised only when their corresponding local interfaces are up and have a private IPv4 address; device-to-device reachability still requires real-device validation.
- The new control channel is wired to the foreground receiver and Compose UI, but it currently authorizes the existing v1 encrypted transfer handshake; it does not yet keep one persistent authenticated session open for the whole batch.
- Pairing authentication remains in the existing transfer handshake. No pairing secret is included in discovery.
- The existing file transfer wire protocol still transfers files in separate sessions. The batch manifest/result models are groundwork only; the single-session multi-file wire protocol and receiver task routing remain to be implemented.
- The room is an approved-peer registry, not yet a continuously monitored online-presence system: the control socket closes after the accept/reject decision. A device may remain listed as authorized until the user ends the session or stops receiving; heartbeat/liveness detection and reconnection are not implemented yet.
- No claim of Android build success or physical-device interoperability is made by this checkpoint.

## Next implementation sequence

1. Wire discovered v2 candidates into the ViewModel and add explicit connection request/accept/reject messages over an authenticated control channel.
2. Keep the existing v1 transfer path as fallback until v2 negotiation passes tests.
3. Implement one authenticated batch session with bounded manifest parsing and independent per-file results.
4. Connect the room UI to real service state, then validate Android-to-Android and Android-to-PC.


## Validation status

- The control-channel unit/integration tests have been added, but no test execution result has been confirmed in this checkpoint.
- The Android build and real-device Android-to-Android interoperability are still unverified. Do not treat the connection flow as production-validated until CI and physical-device checks pass.


## Room and queue implementation update

- The approved-peer room and per-file progress UI are now wired to foreground-service state.
- The queue remains sequential at the transport layer. Each file currently opens its own authenticated v1 transfer connection; the UI must not imply that the requested persistent single-session multi-file protocol is complete.
- The resume mechanism is already implemented in v1 and is keyed by sanitized file name, size, and SHA-256 content digest. It resumes partial bytes after reconnect and checks a completed receipt before sending data again.
- No Android build, unit-test run, physical-device connectivity test, or measured throughput result has been produced for these latest changes.
