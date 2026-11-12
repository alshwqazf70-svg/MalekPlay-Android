# NSTACKX Android integration — implementation checkpoint

## Pinned upstream source

- Project: OpenHarmony `communication_t2stack` (t2stack)
- Upstream repository: https://github.com/openharmony/communication_t2stack
- Reviewed revision: `fbf280db18092eb00bb065e59ce6676c7b85b8c9`
- Upstream describes three distinct capabilities: device discovery (NSTACKX control), file transfer (DFile), and stream transfer (FillP). MalekPlayer's first integration scope is discovery + DFile; FillP is out of scope for ordinary file sharing.

## Integration rules

1. Keep the existing Kotlin/TCP transfer implementation operational as a fallback until real-device acceptance tests pass.
2. Keep NSTACKX behind an internal transport/discovery interface. Do not expose upstream C structs or callbacks to UI/ViewModel code.
3. Do not report NSTACKX as integrated merely because a JNI library loads. Acceptance requires successful init, discovery, authenticated peer selection, DFile send/receive, cancellation, close, and resource cleanup on two physical Android devices.
4. Bind every socket to the selected Android Network where applicable. Never route a Wi-Fi Direct address through an unrelated WLAN interface.
5. Treat QR/MPC2 as the authenticated invitation/session layer. NSTACKX discovery metadata is not an authorization token.
6. Do not replace the current transport or change the app ID, Room schema, navigation, or existing screens as part of the native port.

## Android port work remaining

### A. Native build and platform layer

- Port upstream GN build targets and platform abstractions to Android NDK/CMake (or provide a reproducible GN cross-toolchain); upstream is not an Android Gradle AAR and must not be treated as one.
- Inventory and pin all transitive dependencies, licenses, compiler flags, and required platform services before enabling the native target.
- Implement Android/Bionic adapters for threading, sockets, interface enumeration, timers, logging, and lifecycle. Avoid copying OpenHarmony-only platform code into the Android build.
- Build and inspect ABI outputs for all four ABIs currently declared by the app: `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`. Any deliberate reduction of the app ABI policy must be reviewed and documented.
- Add native init/deinit smoke tests and verify repeated start/stop does not leak threads, descriptors, or callbacks.

### B. DFinder / discovery

- Wrap NSTACKX discovery in a Kotlin-facing repository interface.
- Map discovered peers to stable, authenticated invitation identities; do not trust display names or discovery payloads.
- Handle permission denial, Wi-Fi state changes, duplicate peers, discovery timeout, and cancellation.

### C. DFile / file transfer

- Add the server/client lifecycle, storage-path policy, collision-safe rename behavior, progress callbacks, per-file results, cancellation, and cleanup.
- Connect DFile to the existing authenticated session negotiation. Never send credentials or long-lived secrets in QR payloads.
- Test small/large files, multiple files, interrupted transfer, low storage, duplicate names, and peer disappearance.

## Current verified status

This checkpoint records the reviewed upstream source and acceptance requirements. It does **not** claim that NSTACKX native binaries, JNI bindings, DFinder, or DFile have been built or integrated. Those steps must be implemented and proven by CI/device tests before the feature is enabled in production.

## Acceptance gate

Do not switch the default transport until:
- native builds pass for supported ABIs;
- automated native/Kotlin lifecycle and protocol tests pass;
- two physical Android devices discover, pair, send, and receive files;
- disconnect/reconnect, cancel, and resource cleanup tests pass;
- the existing TCP fallback remains covered and usable.


## Dependency and Bionic audit update — 2026-11-12

See [NSTACKX Android dependency audit](NSTACKX_ANDROID_DEPENDENCY_AUDIT.md) for the exact DFinder source selection, inspected dependency versions and repository snapshots, license findings, and Android/Bionic compatibility gaps.

The audit confirms that upstream Linux platform code cannot be copied wholesale into the Android build. In particular, interface enumeration and `SO_BINDTODEVICE`, CPU-affinity syscalls, UDP GSO, and OpenHarmony logging require Android-specific adaptation or capability-gated fallbacks.

The dependency repository heads inspected in the audit are candidate snapshots only, not approved immutable pins. A reproducible lock and compatibility test are still required before native CMake is added. No Android native target or NSTACKX binary has been built yet.
