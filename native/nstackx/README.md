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

Gradle does not invoke this CMake graph by default yet. Set `-PenableNstackxNativeBuild=true` only after phase 3 platform adaptation and phase 5 JNI integration are ready. This prevents routine application builds from claiming native integration before Android-specific socket/device/log/crypto/filesystem code is complete.

Phase 3 introduces Bionic-specific adapters for interface discovery/binding, Android logging, and thread/runtime helpers under `android/`. The epoll, event-pipe, timerfd and UDP implementation files remain upstream Unix sources pending targeted NDK compilation and the remaining socket/timer compatibility review. Interface binding is best-effort: Android's OS-selected `Network` route remains authoritative when `SO_BINDTODEVICE` is rejected. The Mbed TLS target is the selected Android crypto candidate, but the DFile crypto wrapper still needs API adaptation in phase 4.

No native compilation or device-level transfer is claimed by this phase.
