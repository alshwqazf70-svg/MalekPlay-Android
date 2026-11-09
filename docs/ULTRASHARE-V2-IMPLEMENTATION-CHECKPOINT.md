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
- Extended the authenticated control channel into a persistent TCP control session after acceptance. It exchanges PING/PONG heartbeats every 5 seconds, detects lost peers through socket timeout/EOF, and removes the peer from the room on disconnect. The accepted control session remains alive across sequential file batches until either side ends it or the receiver stops.
- Added a device-room section showing approved peers separately from merely discovered peers, with avatar initials, peer name/address, role, authorization status, and an end-session action. Discovery rows now identify devices as discovered rather than connected.
- Added per-file live progress, transferred/total bytes, and throughput to the queue rows. The existing durable queue continues to retain completed/failed/pending/skipped states independently.
- Verified by source inspection that the current receiver stores partial files with content-derived identities, verifies their SHA-256 digest, resumes from the stored byte offset, and recognizes already completed receipts to avoid retransmitting completed files.
- Updated LAN discovery to send both v2 and v1 probes. New receivers answer v2 with available local interface candidates and continue answering v1 for compatibility. New scanners prefer v2 offers and retain v1-only peers. P2P/hotspot interfaces are advertised as candidates but deliberately marked unverified, so they are not auto-selected merely because an interface exists.

## Important limitations — not yet complete

- Added an authenticated TCP control channel on port 38744. The receiver publishes a pending request to the service/UI, and the sender waits for an authenticated AES-GCM protected accept/reject decision before the UI enables the existing encrypted file-transfer path. Request authentication uses a fresh server challenge, PBKDF2-derived key material, HMAC-SHA256, and domain-separated decision encryption keys. The receiver times out unanswered requests after 30 seconds.
- The v2 offer is currently exchanged over LAN UDP only. Wi-Fi Direct and hotspot candidates are advertised only when their corresponding local interfaces are up and have a private IPv4 address; device-to-device reachability still requires real-device validation.
- The control channel now remains open as a persistent authenticated heartbeat session across sequential file batches. It authorizes the existing v1 encrypted file-transfer handshakes; it does not yet carry all file payloads through one shared data socket.
- Pairing authentication remains in the existing transfer handshake. No pairing secret is included in discovery.
- The existing file transfer wire protocol still transfers files in separate sessions. The batch manifest/result models are groundwork only; the single-session multi-file wire protocol and receiver task routing remain to be implemented.
- The room now reflects live authenticated control-socket presence while the process/service remains alive. It is still a single-peer-at-a-time receiver listener; multi-peer concurrent invitation handling and automatic reconnect/re-authentication are not implemented.
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
- The queue remains sequential at the data-transport layer. Each file currently opens its own authenticated v1 transfer connection; the persistent control socket is shared across the batch, but the requested single data socket for all files is not complete.
- The resume mechanism is already implemented in v1 and is keyed by sanitized file name, size, and SHA-256 content digest. It resumes partial bytes after reconnect and checks a completed receipt before sending data again.
- No Android build, unit-test run, physical-device connectivity test, or measured throughput result has been produced for these latest changes.


## Persistent control-session update

- The accepted TCP control socket now stays open after the encrypted decision. The client sends PING every 5 seconds and expects PONG; the receiver expires a silent session after 18 seconds.
- Explicit local disconnect closes the client or server socket, and both ends remove the peer from their approved room state.
- The control socket persists across file batches. Data still travels through one v1 transfer socket per file; this is not yet a single multi-file data session.
- The receiver currently handles one approved control session at a time. Concurrent multi-peer sessions remain future work.

## Multi-file manifest implementation update — 2026-11-09

- Added `UltraShareBatchProtocol.kt` as a bounded v2 manifest codec. A batch has one UUID and up to 512 file entries; each entry has its own UUID, sanitized name/MIME type, size, and SHA-256 digest.
- The codec validates duplicate IDs, malformed UUIDs, unsafe names/types, file sizes, digest lengths, manifest size, trailing bytes, and aggregate-size overflow before a receiver can act on the manifest.
- Added independent per-file result states (`PENDING`, `TRANSFERRING`, `COMPLETED`, `FAILED`, `SKIPPED`) and validation that results match the manifest exactly. A completed result must account for the full file size.
- Added JVM tests for multi-file round-trip, per-file independent outcomes, duplicate IDs, malformed/trailing payloads, and invalid completion byte counts.
- This is a protocol foundation only: the foreground service and current v1 socket sender/receiver have not yet been switched to this codec. Data still uses a separate v1 socket per file, and the receiver remains single-peer-at-a-time.
- CI was triggered by the new commits. At checkpoint update time the newest run was pending; no successful build or test result is claimed.

## Remaining engineering work

1. Implement the v2 authenticated data-session handshake and keep one data socket open for the complete manifest and all file payloads.
2. Route each manifest entry over that socket with explicit FILE_BEGIN / CHUNK / FILE_END / FILE_RESULT frames, monotonic per-file sequence numbers, bounded frame sizes, and final SHA-256 verification.
3. Persist per-file offsets and receipts against batch ID + file ID + digest, so reconnect resumes only incomplete files and never re-sends verified completed entries.
4. Replace the single blocking accept path with a bounded multi-peer server: one authenticated session worker per peer, explicit limits, isolated cancellation/errors, and no shared mutable transfer streams.
5. Add reconnect/re-authentication with backoff and session resumption; never trust a stale control-room entry as proof that the data socket is alive.
6. Run JVM tests, Android build, instrumentation/network tests, then two-physical-phone tests and repeatable throughput benchmarks against Zapya under the same Wi-Fi/hotspot conditions.

## V2 data-plane and concurrent peer implementation — 2026-11-09

- Added `UltraShareBatchTransport` on a dedicated TCP data port (`38742`); the legacy V1 single-file listener remains on `38741` for older peers.
- The V2 sender prepares one bounded manifest, authenticates once with a fresh challenge and pairing secret, negotiates per-file resume offsets, then sends every file through the same authenticated TCP socket. Each encrypted frame has a monotonically increasing sequence number and AES-GCM authentication.
- V2 data frames identify the file index and chunk length; each file has an explicit end frame and the batch has a final end frame. The receiver returns a result for every manifest entry; the sender maps each result back to its durable queue item.
- The receiver persists partial files and verified completion receipts in per-peer hidden state directories. Resume identity is content-derived (sanitized name + size + SHA-256); completed receipts are rechecked against the final file before the receiver advertises the full offset. Partial files are synced even when the connection drops mid-file.
- The V2 receiver uses one listening server socket and bounded independent workers (maximum four peers by default, configurable up to eight). Sessions from the same source IP are serialized to protect shared resume state; different peers can transfer concurrently.
- The authenticated control listener now accepts up to four concurrent control sessions. The UI still presents one pending approval card at a time; overlapping invitations are rejected instead of overwriting the active decision.
- V2 data transfer requires an active accepted control session on both sides. The V2 discovery offer and QR payload use port 38742; V1 discovery responses still advertise port 38741. The sender falls back to the old V1 path only when the V2 TCP endpoint is unavailable.
- The foreground service now runs the V1 listener and V2 multi-peer listener side by side, preserving old-device compatibility while new peers use V2. V2 batch sending is wired to the durable queue, including retry/resume and restoring claimed items to pending if the user cancels mid-batch.

## Validation boundaries after V2 wiring

- These changes are committed to the feature branch, but the latest Android build and tests are still running/queued. Do not claim compilation or test success until GitHub Actions reports green.
- The v2 wire protocol has not yet been exercised between two physical Android phones. Multi-peer concurrency, reconnect/resume, and data integrity need physical-device verification.
- Throughput has not been measured against Zapya. The current chunk/frame and buffer settings are engineering defaults, not a benchmark result.
- The receiver's approved-control UI allows one pending approval decision at a time, while already accepted control/data sessions can coexist up to the configured concurrency limits.
