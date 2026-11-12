# NSTACKX Android Native Build Graph

This directory contains the Android NDK/CMake build graph for the pinned OpenHarmony NSTACKX source.

## Source preparation

Run from the repository root:

```bash
bash scripts/prepare_nstackx_sources.sh
```

The script checks out the exact immutable revisions in `dependencies.lock` under `third_party/nstackx/`.

## Android configuration

- NDK: `27.2.12479018`
- CMake: `3.22.1`
- Android minimum API: 23 (inherited from the app)
- ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`

The CMake graph defines separate securec, cJSON, utility, DFinder, congestion, and DFile targets and a shared packaging target. It uses the pinned upstream source lists as the starting point.

## Deliberate build gate

Gradle keeps the CMake graph opt-in for local builds. The Android GitHub Actions workflow now passes `-PenableNstackxNativeBuild=true` so CI compiles the native target and exposes remaining NDK/platform errors rather than hiding them.

Phase 3 introduces Bionic-specific adapters for interface discovery/binding (including the upstream-required source-IP-to-P2P/WLAN classification hook), Android logging, thread/runtime helpers, and UDP segmentation fallback under `android/`. The UDP fallback preserves NSTACKX would-block error semantics so the caller can retry when the socket is back-pressured. The epoll, event-pipe and timerfd files remain upstream Unix sources pending targeted NDK compilation and compatibility review. Interface binding is best-effort: Android's OS-selected `Network` route remains authoritative when `SO_BINDTODEVICE` is rejected. The Mbed TLS target is the selected Android crypto candidate, but the DFile crypto wrapper still needs API adaptation in phase 4.

No native compilation or device-level transfer is claimed by this phase.


## JNI and Kotlin bridge

- `android/nstackx_jni.c` exposes DFinder init/thread/discovery/release and DFile server/client/send/close entry points.
- `NstackxNativeBridge.kt` is the Kotlin facade; `NstackxAndroidDiscoverySession.kt` selects an Android Network and supplies its handle before native sockets are created.
- DFinder results are supplemental discovery metadata. They are matched by stable peer ID and do not authenticate peers.
- DFile keys must be 16-byte keys derived from an already authenticated session. Never pass the QR/PIN itself to DFile.
- Ultra Share's existing UDP negotiation, QR approval, encrypted TCP transport, and fallback remain active until DFile passes device acceptance tests.

The native CMake graph and JNI/DFile APIs have not yet passed an Android NDK build in CI. Runtime discovery, DFile crypto interoperability, cancellation/resume, and two-device tests are still outstanding.
