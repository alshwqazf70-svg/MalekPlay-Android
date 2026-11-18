# Ultra Share V2 — Current Protocol and Engineering Status

Status: V2 is the only Android file-data protocol on `feature/zapya-engineering-analysis`.
The former MPU1/V1 sender, receiver, listener, retry path and downgrade fallback have been retired.
NSTACKX/DFinder and DFile are also removed from the Android build and runtime.
Older peers that do not implement V2 are unsupported; the application must fail clearly rather than silently downgrade.

## 1. Runtime architecture

### Discovery and route selection
- Android LAN discovery uses UDP port 38742.
- Discovery advertisements contain peer identity and route candidates, not pairing secrets.
- LAN, hotspot and Wi-Fi Direct candidates are hints only; a candidate is probed before use.
- Android sockets are bound to the selected `Network` where possible. A process-default route is a final fallback, not permission to route a Wi-Fi Direct address through an unrelated network.
- Wi-Fi Direct and LocalOnlyHotspot use Android platform APIs. No NSTACKX library is loaded.

### Pairing and control plane (MPC2)
- The control listener uses TCP port 38744.
- The QR/PIN invitation carries a short-lived pairing secret and local route hints.
- The control exchange uses a fresh 32-byte challenge, HMAC-SHA256 authentication and AES-GCM-protected decision/session frames.
- A nine-digit PIN is expanded with PBKDF2-HMAC-SHA256 (210,000 iterations); the implementation also accepts a 32-byte hexadecimal secret for internal compatibility.
- An accepted MPC2 session and the V2 data handshake are both required before file data is accepted.
- Control sessions are temporary and held in memory; heartbeat/close handling retires stale sessions.

### V2 data plane
- The V2 receiver listens on TCP port 38742.
- One authenticated TCP connection carries one bounded manifest and all files in that batch.
- The manifest uses magic `MPU2`, protocol version 2, a batch UUID and per-file UUIDs.
- Limits currently implemented:
  - 512 files per batch;
  - 100 GiB maximum per file;
  - 512 KiB maximum encoded manifest;
  - 180 UTF-8 bytes per filename;
  - 192 UTF-8 bytes per MIME type;
  - 512 KiB data-frame payload.
- The receiver accepts a bounded number of concurrent peer sessions (default four). Each peer's resume-state access is serialized independently.
- File frames are AES-GCM authenticated. Directional keys are derived separately for client-to-server and server-to-client traffic. Each frame nonce combines a fresh four-byte prefix with a monotonically increasing eight-byte sequence.
- TCP supplies ordered delivery, congestion control and retransmission. V2 deliberately does not add a redundant per-chunk ACK/retry loop.

## 2. File integrity, resume and publication

1. The sender scans each selected source to compute its SHA-256 digest and exact size before constructing the manifest.
2. The sender re-reads the source while streaming and verifies that the content did not change after the manifest was prepared.
3. The receiver writes to app-private partial files.
4. Resume checkpoints are committed after partial data is synchronized; the checkpoint interval is currently 4 MiB.
5. Resume identity includes the sanitized filename, exact size and SHA-256 digest. Bytes beyond the last durable checkpoint are truncated.
6. After a file ends, the receiver verifies its full SHA-256 digest.
7. A completed receipt is written through a synchronized temporary file before the verified partial is atomically renamed to a collision-safe destination.
8. The receiver returns a result for each file. A completed file remains completed even if another file in the batch fails.

The implementation scales its verification read timeout with declared batch size and caps it below the Android data-sync foreground-service time limit.

## 3. Desktop companion

The Python desktop companion implements the same V2 manifest, key derivation, AES-GCM frame format, resume identity and result encoding. Shared manifest/key-derivation vectors are present in Kotlin and Python tests.

A passing Python-to-Python loopback test is not evidence of Android-to-desktop interoperability. Cross-language integration must run an actual Kotlin V2 endpoint against the Python V2 endpoint.

## 4. Retired paths

The following are not part of the current V2 runtime:
- MPU1/V1 file-data framing and its one-file transfer listener;
- V1 fallback/downgrade branches;
- NSTACKX JNI discovery;
- DFile native sender/receiver and DFile staging/manifest helpers;
- native NSTACKX source acquisition, NDK and CMake build wiring.

`UltraShareTransport.kt` is retained only as a shared support layer for validation, pairing-key derivation, file metadata and safe publication. It is not the former V1 file-transfer engine and must not be deleted until its shared call sites are moved to a correctly named V2 support module.

## 5. Known verification gaps

- Android CI currently runs emulator runtime tests on API 23, 29 and 35. This covers Android 6, Android 10 and Android 15 checkpoints, not every API level from 23 through 35.
- JVM tests cover manifest validation, wire vectors, checkpoint recovery, durable receipts, listener readiness and cancellation. A full Kotlin sender-to-Kotlin receiver batch integration test is still required.
- The desktop test named `test_android_v2_batch_sender_and_desktop_receiver_interoperate` currently invokes Python `send_batch_v2` and Python `receive_batch_v2`; it is not an Android-to-desktop test.
- Two physical Android devices are still required to validate bidirectional transfer, Wi-Fi Direct, hotspot, shared Wi-Fi, large files, screen-off behavior, process interruption and resume.
- No repeatable same-network benchmark currently proves speed parity or superiority to Zapya.
- The current share-file policy rejects APK/PDF but still accepts generic MIME types and ZIP archives. If the product contract is media-only, Android, desktop and folder-archive paths must be narrowed together; UI filtering alone is not a security boundary.
- There is no explicit aggregate batch-size ceiling or complete destination free-space preflight yet.
- Frame size is fixed at 512 KiB; it has not been benchmarked across the full Android 6–15 device range.

## 6. Acceptance gates before declaring V2 complete

1. Keep V2 as the only file-data protocol; do not reintroduce MPU1 or NSTACKX/DFile without a separate, reviewed architecture decision.
2. Add true Kotlin↔Python V2 integration tests and a Kotlin sender↔receiver end-to-end batch test.
3. Test multiple video/audio/image files, empty files, duplicate names, large files, content mutation, digest mismatch, disk-full and cancellation.
4. Verify resume after Wi-Fi loss, app backgrounding, process death and receiver restart.
5. Exercise LAN, hotspot and Wi-Fi Direct on real Android devices, with correct Android `Network` binding.
6. Run emulator checks at the declared minimum and current target APIs, then test representative OEM devices.
7. Benchmark frame sizes and peer concurrency on identical hardware/network conditions before changing defaults.
8. Update this document and README whenever implementation status changes; never infer physical-device success from a green build alone.
