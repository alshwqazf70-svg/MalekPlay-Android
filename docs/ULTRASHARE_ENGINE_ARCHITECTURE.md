# UltraShare Engine — Native Android Architecture

This document describes the in-app engine used by MalekPlay. NSTACKX/DFinder and DFile are retired; the Android app no longer builds or loads their native libraries.

## Runtime layers

1. **Discovery**
   - LAN discovery and offers: `UltraShareDiscovery` over UDP.
   - Direct proximity path: Android Wi-Fi Direct through `UltraShareWifiDirect` and the QR/DNS-SD invitation lifecycle.
   - Network candidates are hints only; a candidate is probed before it is used for data transfer.

2. **Pairing and session authorization**
   - `UltraShareControlChannel` owns the MPC2 TCP control channel.
   - The short-lived QR/PIN secret authenticates the peer; the decision frame is encrypted.
   - Accepted sessions are temporary and in-memory. The control socket heartbeat retires stale sessions.
   - The control protocol no longer derives, stores, or negotiates DFile keys or ports.

3. **Data plane**
   - `UltraShareBatchTransport` over TCP is the only file data plane.
   - One authenticated TCP connection carries a bounded batch manifest and its files.
   - `UltraShareCore` contains shared validation, pairing-key, metadata and safe-publication helpers; it is not a transport engine.
   - Android and desktop enforce media-only payloads: video, audio and raster images. Documents, archives, application packages and SVG are rejected at the protocol boundary. The folder-to-ZIP send/extract workflow has been removed.
   - The receiver validates the manifest, file lengths, transfer identity and SHA-256 digests before publishing received media.
   - Resume state is checkpointed atomically; only checkpointed bytes are eligible for resume.
   - The retired MPU1 single-file sender/receiver and all V1 downgrade paths have been removed. Older clients without V2 are reported as unsupported; the app never silently switches protocols.

4. **Android lifecycle**
   - `UltraShareForegroundService` owns listening sockets and transfer jobs beyond the screen/ViewModel lifecycle.
   - Transfer wake/Wi-Fi locks are acquired for active transfers and released when they finish or the service shuts down.
   - Android force-stop and system foreground-service time limits remain system boundaries; the app cannot promise transfer continuation after force-stop.

## Removed native path

- Removed NSTACKX JNI bridge and Android discovery lifecycle.
- Removed DFile sender/receiver implementations and DFile staging/manifest/publishing helpers.
- Removed the native NSTACKX source tree and source-preparation script.
- Removed NDK/CMake setup and NSTACKX build flags from Android CI.
- New MPC2 sessions no longer negotiate DFile endpoints; TCP V2 is the only data plane.
- The former MPU1 single-file data plane, listener loop, retry helper and automatic downgrade branches were removed.

## Verification boundary

CI is configured for compilation, JVM protocol/transport tests, desktop Python tests and emulator checkpoints at API 23, 29 and 35. The current GitHub commit-status query did not expose a completed check result for the latest changes. The test suite still needs a true Kotlin↔Python V2 socket integration test and a full Kotlin sender↔receiver batch integration test. Emulator tests do not prove Wi-Fi Direct interoperability between two physical Android devices. Final acceptance still requires two-phone tests covering discovery, QR pairing, bidirectional transfer, large files, interrupted transfer/resume, and screen-off/background behavior.
