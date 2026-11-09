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
- Updated LAN discovery to send both v2 and v1 probes. New receivers answer v2 with available local interface candidates and continue answering v1 for compatibility. New scanners prefer v2 offers and retain v1-only peers. P2P/hotspot interfaces are advertised as candidates but deliberately marked unverified, so they are not auto-selected merely because an interface exists.

## Important limitations — not yet complete

- Added an authenticated TCP control channel on port 38744. The receiver publishes a pending request to the service/UI, and the sender waits for an authenticated AES-GCM protected accept/reject decision before the UI enables the existing encrypted file-transfer path. Request authentication uses a fresh server challenge, PBKDF2-derived key material, HMAC-SHA256, and domain-separated decision encryption keys. The receiver times out unanswered requests after 30 seconds.
- The v2 offer is currently exchanged over LAN UDP only. Wi-Fi Direct and hotspot candidates are advertised only when their corresponding local interfaces are up and have a private IPv4 address; device-to-device reachability still requires real-device validation.
- The new control channel is wired to the foreground receiver and Compose UI, but it currently authorizes the existing v1 encrypted transfer handshake; it does not yet keep one persistent authenticated session open for the whole batch.
- Pairing authentication remains in the existing transfer handshake. No pairing secret is included in discovery.
- The existing file transfer wire protocol still transfers files in separate sessions. The batch manifest/result models are groundwork only; the single-session multi-file wire protocol and receiver task routing remain to be implemented.
- The Compose group room, peer avatar/profile presentation, and per-device acceptance UI remain pending.
- No claim of Android build success or physical-device interoperability is made by this checkpoint.

## Next implementation sequence

1. Wire discovered v2 candidates into the ViewModel and add explicit connection request/accept/reject messages over an authenticated control channel.
2. Keep the existing v1 transfer path as fallback until v2 negotiation passes tests.
3. Implement one authenticated batch session with bounded manifest parsing and independent per-file results.
4. Connect the room UI to real service state, then validate Android-to-Android and Android-to-PC.


## Validation status

- The control-channel unit/integration tests have been added, but no test execution result has been confirmed in this checkpoint.
- The Android build and real-device Android-to-Android interoperability are still unverified. Do not treat the connection flow as production-validated until CI and physical-device checks pass.
