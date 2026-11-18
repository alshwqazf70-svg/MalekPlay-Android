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
   - New batch transfers use `UltraShareBatchTransport` over TCP.
   - One authenticated TCP connection carries a batch manifest and its files.
   - The receiver validates the manifest, file lengths, and SHA-256 digests before publishing received files.
   - Resume state is checkpointed atomically; only checkpointed bytes are eligible for resume.
   - The single-file `UltraShareTransport` path remains for compatibility with older peers.

4. **Android lifecycle**
   - `UltraShareForegroundService` owns listening sockets and transfer jobs beyond the screen/ViewModel lifecycle.
   - Transfer wake/Wi-Fi locks are acquired for active transfers and released when they finish or the service shuts down.
   - Android force-stop and system foreground-service time limits remain system boundaries; the app cannot promise transfer continuation after force-stop.

## Removed native path

- Removed NSTACKX JNI bridge and Android discovery lifecycle.
- Removed DFile sender/receiver implementations and DFile staging/manifest/publishing helpers.
- Removed the native NSTACKX source tree and source-preparation script.
- Removed NDK/CMake setup and NSTACKX build flags from Android CI.
- New MPC2 sessions no longer negotiate DFile endpoints; TCP is the only data plane.

## Verification boundary

CI can verify compilation, JVM protocol/transport tests, and emulator runtime tests. These do not prove Wi-Fi Direct interoperability between two physical Android devices. Final acceptance still requires two-phone tests covering discovery, QR pairing, bidirectional transfer, large files, interrupted transfer/resume, and screen-off/background behavior.
